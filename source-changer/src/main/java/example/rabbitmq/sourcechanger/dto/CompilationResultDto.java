package example.rabbitmq.sourcechanger.dto;

/** Result of a compilation request. */
public record CompilationResultDto(boolean success, ResultType resultType, String message) {

  public enum ResultType {
    SUCCESS,
    FAILURE,
    /** Worker is under maintenance; the caller retries against another worker. */
    MAINTENANCE
  }
}
