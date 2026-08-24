package example.rabbitmq.sourcechanger.exception;

/** Raised when a request/reply call over RabbitMQ fails or is interrupted. */
public class MessageSendException extends RuntimeException {

  public MessageSendException(String message, Throwable cause) {
    super(message, cause);
  }
}
