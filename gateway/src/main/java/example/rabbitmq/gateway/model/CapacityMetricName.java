package example.rabbitmq.gateway.model;

/** Capacity metrics watched by the scaling engine. */
public enum CapacityMetricName {
  TOTAL_SOURCES_SIZE_BYTES,
  COMPILATION_REQUEST_DELIVERY_TIME,
  COMPLETION_REQUEST_DELIVERY_TIME
}
