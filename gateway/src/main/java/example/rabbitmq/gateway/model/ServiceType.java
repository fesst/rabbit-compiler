package example.rabbitmq.gateway.model;

/** Services that can be scaled by the gateway. */
public enum ServiceType {
  SOURCE_CHANGER("source-changer"),
  COMPILATION("compilation"),
  COMPLETION("completion");

  private final String applicationName;

  ServiceType(String applicationName) {
    this.applicationName = applicationName;
  }

  public String applicationName() {
    return applicationName;
  }
}
