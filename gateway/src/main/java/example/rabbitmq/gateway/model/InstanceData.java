package example.rabbitmq.gateway.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of running instances and their capacity metrics.
 *
 * <p>Keyed by {@link ServiceType}, then by instance run id, then by metric name.
 */
public record InstanceData(
        Map<ServiceType, Map<String, Map<CapacityMetricName, Double>>> metrics
) {

    private static final InstanceData EMPTY = new InstanceData(Map.of());

    public static InstanceData empty() {
        return EMPTY;
    }

    public Set<String> runIds(ServiceType type) {
        return metrics.getOrDefault(type, Map.of()).keySet();
    }

    public List<CapacityMetric> metrics(ServiceType type, CapacityMetricName name) {
        return metrics.getOrDefault(type, Map.of()).entrySet().stream()
                .map(entry -> {
                    Double value = entry.getValue().get(name);
                    return value == null ? null : new CapacityMetric(entry.getKey(), name, value);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
