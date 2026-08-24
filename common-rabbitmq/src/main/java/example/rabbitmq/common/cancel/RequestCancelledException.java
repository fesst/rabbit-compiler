package example.rabbitmq.common.cancel;

/** Thrown when a request has been cancelled at a specific point of execution. */
public class RequestCancelledException extends RuntimeException {

    public RequestCancelledException(String cancellationPoint) {
        super("Request cancelled at: " + cancellationPoint);
    }
}
