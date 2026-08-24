package example.rabbitmq.common.cancel;

/** Kind of long-running operation that can be cancelled. */
public enum CancellableRequestType {
    COMPILATION,
    COMPLETION,
    SAVE
}
