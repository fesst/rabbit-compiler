package example.rabbitmq.sourcechanger.dto;

/** Result of a compilation request (mirror of the source-changer DTO). */
public record CompilationResultDto(boolean success, ResultType resultType, String message) {

  public enum ResultType {
    SUCCESS,
    FAILURE,
    /** Worker is under maintenance; the caller retries against another worker. */
    MAINTENANCE
  }
}
