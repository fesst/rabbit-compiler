package example.rabbitmq.common.cancel;

import java.util.UUID;

/**
 * A request that can be cancelled.
 *
 * <p>A special request id (the zero UUID) denotes the cancellation command
 * itself: registering it cancels whatever request is currently active for the
 * same {@code (type, scope)} key.
 */
public record CancellableRequest(
        CancellableRequestType type,
        CancellableRequestScope scope,
        UUID requestId,
        boolean cancellable
) {

    public static final UUID CANCELLATION_REQUEST_ID = new UUID(0L, 0L);

    public static CancellableRequest cancellationRequest(CancellableRequestType type, CancellableRequestScope scope) {
        return new CancellableRequest(type, scope, CANCELLATION_REQUEST_ID, true);
    }

    public static boolean isCancellationRequest(UUID requestId) {
        return CANCELLATION_REQUEST_ID.equals(requestId);
    }
}
