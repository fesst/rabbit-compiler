package example.rabbitmq.gateway.util;

import example.rabbitmq.gateway.model.CapacityMetric;

import java.util.Collection;

/** Helpers for comparing capacity metrics against scaling limits. */
public final class MetricUtils {

    private MetricUtils() {
    }

    public static boolean anyValueExceeds(Collection<CapacityMetric> metrics, double limit) {
        return metrics.stream().anyMatch(m -> m.currentValue() > limit);
    }

    public static boolean allValuesBelow(Collection<CapacityMetric> metrics, double limit) {
        return !metrics.isEmpty() && metrics.stream().allMatch(m -> m.currentValue() < limit);
    }

    public static String firstRunIdBelow(Collection<CapacityMetric> metrics, double limit) {
        return metrics.stream()
                .filter(m -> m.currentValue() < limit)
                .map(CapacityMetric::applicationRunId)
                .findFirst()
                .orElse(null);
    }
}
