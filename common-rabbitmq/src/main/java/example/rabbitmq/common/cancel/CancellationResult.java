package example.rabbitmq.common.cancel;

import java.util.UUID;

/**
 * Published when a new request registers and cancels the previously active one.
 * Other instances receive it and register the cancellation locally.
 */
public record CancellationResult(
        CancellableRequestType type,
        CancellableRequestScope scope,
        UUID activeRequestId,
        boolean cancellable,
        UUID cancelledRequestId
) {

    public CancellableRequest toRequest() {
        return new CancellableRequest(type, scope, activeRequestId, cancellable);
    }
}
