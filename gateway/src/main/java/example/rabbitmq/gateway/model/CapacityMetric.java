package example.rabbitmq.gateway.model;

/** A single capacity metric value reported by one instance. */
public record CapacityMetric(String applicationRunId, CapacityMetricName metricName, double currentValue) {
}
