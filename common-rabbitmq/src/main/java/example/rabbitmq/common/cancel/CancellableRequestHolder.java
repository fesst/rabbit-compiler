package example.rabbitmq.common.cancel;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Binds the currently executing cancellable request to the thread.
 *
 * <pre>
 * CancellableRequestHolder.doWithNewRequest(cancellationService, request, () -&gt; {
 *     CancellableRequestHolder.throwIfRequestCancelled("checkpoint");
 *     return longRunningWork();
 * });
 * </pre>
 */
public final class CancellableRequestHolder {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private CancellableRequestHolder() {
    }

    /**
     * Registers {@code request} (cancelling any previous active one for the same
     * key) and runs {@code action} inside its context.
     *
     * @throws RequestCancelledException if the request cannot be registered,
     *         for example when it was already cancelled by a concurrent request
     */
    public static <T> T doWithNewRequest(
            SharedCancellationService cancellationService,
            CancellableRequest request,
            Supplier<T> action
    ) {
        boolean started = cancellationService.startRequestAndCancelPrevious(request);
        if (!started) {
            throw new RequestCancelledException("on-starting-request");
        }
        return doWithExistingRequest(cancellationService, request, action);
    }

    /** Runs {@code action} assuming {@code request} is already registered. */
    public static <T> T doWithExistingRequest(
            SharedCancellationService cancellationService,
            CancellableRequest request,
            Supplier<T> action
    ) {
        HOLDER.set(new Context(cancellationService, request));
        try {
            return action.get();
        } finally {
            HOLDER.remove();
            cancellationService.finishRequest(request);
        }
    }

    /** Returns the request bound to the current thread. */
    public static CancellableRequest requireCurrentRequest() {
        return Objects.requireNonNull(HOLDER.get(), "No cancellable request bound to current thread").request();
    }

    public static boolean isRequestCancelled() {
        Context context = requireContext();
        return context.cancellationService().isRequestCancelled(context.request());
    }

    /** Throws if the current request has been cancelled. */
    public static void throwIfRequestCancelled(String cancellationPoint) {
        if (isRequestCancelled()) {
            throw new RequestCancelledException(cancellationPoint);
        }
    }

    private static Context requireContext() {
        return Objects.requireNonNull(HOLDER.get(), "No cancellable request bound to current thread");
    }

    private record Context(SharedCancellationService cancellationService, CancellableRequest request) {
    }
}
