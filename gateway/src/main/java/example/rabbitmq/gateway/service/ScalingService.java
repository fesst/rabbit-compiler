package example.rabbitmq.gateway.service;

import example.rabbitmq.gateway.dataprovider.ScalingProvider;
import example.rabbitmq.gateway.model.CapacityMetric;
import example.rabbitmq.gateway.model.CapacityMetricName;
import example.rabbitmq.gateway.model.ServiceType;
import example.rabbitmq.gateway.util.MetricUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scaling decision engine.
 *
 * <p>
 * Each concrete service watches one capacity metric and decides whether its
 * pool of workers should grow or shrink:
 *
 * <pre>
 * NoScaling ──(metric &gt; max)──▶ ScalingUp   ──(new instance | timeout)──▶ NoScaling
 * NoScaling ──(metric &lt; min)──▶ ScalingDown ──(instance gone | timeout)──▶ NoScaling
 * </pre>
 *
 * <p>
 * A new scaling action cannot start until the previous one completes
 * (observed via the set of running instances changing) or times out.
 */
public interface ScalingService {
  void run();
}

abstract class BaseScalingService implements ScalingService {

  private static final Logger log = LoggerFactory.getLogger(BaseScalingService.class);

  protected volatile ScalingState scalingState;

  @PostConstruct
  void init() {
    scalingState = new NoScaling(currentTimeMillis());
  }

  protected abstract long currentTimeMillis();

  protected abstract void maybeStartScaling();

  protected abstract void maybeStopScaling();

  @Override
  public void run() {
    if (isScalingStarted()) {
      maybeStopScaling();
    }
    if (!isScalingStarted()) {
      maybeStartScaling();
    }
  }

  protected boolean isScalingStarted() {
    return !(scalingState instanceof NoScaling);
  }

  protected void startScalingUp(Set<String> runIdsBeforeScaling) {
    log.debug("Scaling up started");
    scalingState = new ScalingUp(currentTimeMillis(), runIdsBeforeScaling);
  }

  protected void startScalingDown(String runIdToStop) {
    log.debug("Scaling down started");
    scalingState = new ScalingDown(currentTimeMillis(), runIdToStop);
  }

  protected void stopScaling() {
    log.debug("Scaling stopped");
    scalingState = new NoScaling(currentTimeMillis());
  }

  protected sealed interface ScalingState permits NoScaling, ScalingUp, ScalingDown {
    long startTimeMillis();
  }

  protected record NoScaling(long startTimeMillis) implements ScalingState {
  }

  protected record ScalingUp(long startTimeMillis, Set<String> runIdsBeforeScaling) implements ScalingState {
  }

  protected record ScalingDown(long startTimeMillis, String runIdToStop) implements ScalingState {
  }
}

abstract class ServiceMetricsScalingService extends BaseScalingService {

  private static final Logger log = LoggerFactory.getLogger(ServiceMetricsScalingService.class);

  protected final Clock clock;
  protected final ScalingProvider scalingProvider;
  protected final InstanceRegistry instanceRegistry;

  protected final String templateName;
  protected final int maxInstanceCount;
  protected final int minInstanceCount;
  protected final long scalingTimeoutMillis;
  protected final long noScalingIntervalMillis;
  protected final ServiceType scalingServiceType;
  protected final ServiceType metricsServiceType;
  protected final CapacityMetricName metricName;

  protected ServiceMetricsScalingService(
      Clock clock,
      ScalingProvider scalingProvider,
      InstanceRegistry instanceRegistry,
      String templateName,
      int maxInstanceCount,
      int minInstanceCount,
      long scalingTimeoutMillis,
      long noScalingIntervalMillis,
      ServiceType scalingServiceType,
      ServiceType metricsServiceType,
      CapacityMetricName metricName) {
    this.clock = clock;
    this.scalingProvider = scalingProvider;
    this.instanceRegistry = instanceRegistry;
    this.templateName = templateName;
    this.maxInstanceCount = maxInstanceCount;
    this.minInstanceCount = minInstanceCount;
    this.scalingTimeoutMillis = scalingTimeoutMillis;
    this.noScalingIntervalMillis = noScalingIntervalMillis;
    this.scalingServiceType = scalingServiceType;
    this.metricsServiceType = metricsServiceType;
    this.metricName = metricName;
  }

  @Override
  protected long currentTimeMillis() {
    return clock.millis();
  }

  @Override
  protected void maybeStartScaling() {
    long remaining = scalingState.startTimeMillis() + noScalingIntervalMillis - clock.millis();
    if (remaining > 0) {
      log.info("No-scaling interval still active for {} ({} ms left)", scalingServiceType, remaining);
      return;
    }

    List<CapacityMetric> metrics = instanceRegistry.getData().metrics(metricsServiceType, metricName);
    if (metrics.isEmpty()) {
      log.warn("No {} metrics for {} found", metricName, metricsServiceType);
      return;
    }

    if (anyValueExceedsMax(metrics)) {
      maybeStartScalingUp();
    } else {
      maybeStartScalingDown(metrics);
    }
  }

  private void maybeStartScalingUp() {
    Set<String> runIds = runIds();
    if (runIds.size() < maxInstanceCount) {
      log.info("Scaling {} up", scalingServiceType);
      if (scalingProvider.scaleUp(templateName)) {
        startScalingUp(runIds);
      }
    } else {
      log.info("{} already at max instances", scalingServiceType);
    }
  }

  private void maybeStartScalingDown(List<CapacityMetric> metrics) {
    Set<String> runIds = runIds();
    if (runIds.size() > minInstanceCount) {
      String runIdToStop = findRunIdToScaleDown(metrics);
      if (runIdToStop != null && scalingProvider.scaleDown(templateName, runIdToStop)) {
        log.info("Scaling {} down, stopping {}", scalingServiceType, runIdToStop);
        startScalingDown(runIdToStop);
      }
    } else {
      log.info("{} already at min instances", scalingServiceType);
    }
  }

  @Override
  protected void maybeStopScaling() {
    if (scalingState instanceof ScalingUp state) {
      if (newRunIdsStarted(state) || timeoutExceeded(state)) {
        if (timeoutExceeded(state)) {
          log.warn("{} scale-up timed out after {} ms", scalingServiceType, scalingTimeoutMillis);
        }
        stopScaling();
      }
    } else if (scalingState instanceof ScalingDown state) {
      if (runIdStopped(state) || timeoutExceeded(state)) {
        if (timeoutExceeded(state)) {
          log.warn("{} scale-down timed out after {} ms", scalingServiceType, scalingTimeoutMillis);
        }
        stopScaling();
      }
    }
  }

  private boolean newRunIdsStarted(ScalingUp state) {
    Set<String> ids = new HashSet<>(runIds());
    ids.removeAll(state.runIdsBeforeScaling());
    return !ids.isEmpty();
  }

  private boolean runIdStopped(ScalingDown state) {
    return !runIds().contains(state.runIdToStop());
  }

  private boolean timeoutExceeded(ScalingState state) {
    return clock.millis() - state.startTimeMillis() > scalingTimeoutMillis;
  }

  protected Set<String> runIds() {
    return instanceRegistry.getData().runIds(scalingServiceType);
  }

  protected abstract boolean anyValueExceedsMax(List<CapacityMetric> metrics);

  protected abstract String findRunIdToScaleDown(List<CapacityMetric> metrics);
}

@Component
@ConditionalOnProperty(value = "example.scaling.enabled", havingValue = "true")
class SourceChangerScalingService extends ServiceMetricsScalingService {

  private final double modelsMemoryBytesMax;
  private final double modelsMemoryBytesMin;

  SourceChangerScalingService(
      Clock clock,
      ScalingProvider scalingProvider,
      InstanceRegistry instanceRegistry,
      @Value("${example.scaling.template.source-changer}") String templateName,
      @Value("${example.scaling.max-count.source-changer:3}") int maxCount,
      @Value("${example.scaling.min-count.source-changer:1}") int minCount,
      @Value("${example.scaling.timeout-ms:300000}") long scalingTimeoutMillis,
      @Value("${example.scaling.no-scaling-interval-ms:30000}") long noScalingIntervalMillis,
      @Value("${example.scaling.limits.models-memory-bytes-max:1000000000}") double modelsMemoryBytesMax,
      @Value("${example.scaling.limits.models-memory-bytes-min:100000000}") double modelsMemoryBytesMin) {
    super(clock, scalingProvider, instanceRegistry, templateName, maxCount, minCount,
        scalingTimeoutMillis, noScalingIntervalMillis,
        ServiceType.SOURCE_CHANGER, ServiceType.SOURCE_CHANGER,
        CapacityMetricName.TOTAL_SOURCES_SIZE_BYTES);
    this.modelsMemoryBytesMax = modelsMemoryBytesMax;
    this.modelsMemoryBytesMin = modelsMemoryBytesMin;
  }

  @Override
  protected boolean anyValueExceedsMax(List<CapacityMetric> metrics) {
    return MetricUtils.anyValueExceeds(metrics, modelsMemoryBytesMax);
  }

  @Override
  protected String findRunIdToScaleDown(List<CapacityMetric> metrics) {
    return MetricUtils.firstRunIdBelow(metrics, modelsMemoryBytesMin);
  }
}

@Component
@ConditionalOnProperty(value = "example.scaling.enabled", havingValue = "true")
class CompletionScalingService extends ServiceMetricsScalingService {

  private final double deliveryTimeMaxSecs;
  private final double deliveryTimeMinSecs;

  CompletionScalingService(
      Clock clock,
      ScalingProvider scalingProvider,
      InstanceRegistry instanceRegistry,
      @Value("${example.scaling.template.completion}") String templateName,
      @Value("${example.scaling.max-count.completion:5}") int maxCount,
      @Value("${example.scaling.min-count.completion:1}") int minCount,
      @Value("${example.scaling.timeout-ms:300000}") long scalingTimeoutMillis,
      @Value("${example.scaling.no-scaling-interval-ms:30000}") long noScalingIntervalMillis,
      @Value("${example.scaling.limits.completion-delivery-time-max:5}") double deliveryTimeMaxSecs,
      @Value("${example.scaling.limits.completion-delivery-time-min:0.5}") double deliveryTimeMinSecs) {
    super(clock, scalingProvider, instanceRegistry, templateName, maxCount, minCount,
        scalingTimeoutMillis, noScalingIntervalMillis,
        ServiceType.COMPLETION, ServiceType.SOURCE_CHANGER,
        CapacityMetricName.COMPLETION_REQUEST_DELIVERY_TIME);
    this.deliveryTimeMaxSecs = deliveryTimeMaxSecs;
    this.deliveryTimeMinSecs = deliveryTimeMinSecs;
  }

  @Override
  protected boolean anyValueExceedsMax(List<CapacityMetric> metrics) {
    return MetricUtils.anyValueExceeds(metrics, deliveryTimeMaxSecs);
  }

  @Override
  protected String findRunIdToScaleDown(List<CapacityMetric> metrics) {
    if (!MetricUtils.allValuesBelow(metrics, deliveryTimeMinSecs)) {
      return null;
    }
    Set<String> runIds = runIds();
    return runIds.isEmpty() ? null : runIds.stream().min(Comparator.naturalOrder()).orElse(null);
  }
}

@Component
@ConditionalOnProperty(value = "example.scaling.enabled", havingValue = "true")
class CompilationScalingService extends ServiceMetricsScalingService {

  private final double deliveryTimeMaxSecs;
  private final double deliveryTimeMinSecs;

  CompilationScalingService(
      Clock clock,
      ScalingProvider scalingProvider,
      InstanceRegistry instanceRegistry,
      @Value("${example.scaling.template.compilation}") String templateName,
      @Value("${example.scaling.max-count.compilation:5}") int maxCount,
      @Value("${example.scaling.min-count.compilation:1}") int minCount,
      @Value("${example.scaling.timeout-ms:300000}") long scalingTimeoutMillis,
      @Value("${example.scaling.no-scaling-interval-ms:30000}") long noScalingIntervalMillis,
      @Value("${example.scaling.limits.compilation-delivery-time-max:30}") double deliveryTimeMaxSecs,
      @Value("${example.scaling.limits.compilation-delivery-time-min:1}") double deliveryTimeMinSecs) {
    super(clock, scalingProvider, instanceRegistry, templateName, maxCount, minCount,
        scalingTimeoutMillis, noScalingIntervalMillis,
        ServiceType.COMPILATION, ServiceType.SOURCE_CHANGER,
        CapacityMetricName.COMPILATION_REQUEST_DELIVERY_TIME);
    this.deliveryTimeMaxSecs = deliveryTimeMaxSecs;
    this.deliveryTimeMinSecs = deliveryTimeMinSecs;
  }

  @Override
  protected boolean anyValueExceedsMax(List<CapacityMetric> metrics) {
    return MetricUtils.anyValueExceeds(metrics, deliveryTimeMaxSecs);
  }

  @Override
  protected String findRunIdToScaleDown(List<CapacityMetric> metrics) {
    if (!MetricUtils.allValuesBelow(metrics, deliveryTimeMinSecs)) {
      return null;
    }
    Set<String> runIds = runIds();
    return runIds.isEmpty() ? null : runIds.stream().min(Comparator.naturalOrder()).orElse(null);
  }
}
