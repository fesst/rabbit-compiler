package example.rabbitmq.common.cancel;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Tracks active and cancelled requests for this instance and synchronises
 * cancellations with other instances over RabbitMQ.
 *
 * <p>Only one request per {@code (type, scope)} key may be active. Registering a
 * new request with the same key cancels the previous one. A cancellation is
 * broadcast to every instance so a long-running operation can be interrupted no
 * matter which instance started it. Registrations are cleaned up after a
 * configurable lifetime.
 */
public class SharedCancellationService {

    private final RabbitTemplate rabbitTemplate;
    private final boolean broadcastCancellation;

    @Value("${example.mq.shared-cancellation.exchange}")
    private String sharedCancellationExchange;

    @Value("${example.shared-cancellation.request-lifetime}")
    private Duration requestLifetime;

    private final Map<Key, Entry> requests = new ConcurrentHashMap<>();

    public SharedCancellationService(RabbitTemplate rabbitTemplate, boolean broadcastCancellation) {
        this.rabbitTemplate = rabbitTemplate;
        this.broadcastCancellation = broadcastCancellation;
    }

    @Scheduled(fixedDelayString = "${example.shared-cancellation.request-lifetime}")
    void cleanup() {
        long now = System.nanoTime();
        requests.values().removeIf(entry -> entry.registrationTime() + requestLifetime.toNanos() < now);
    }

    /**
     * Registers {@code request}, cancelling the previously active one for the
     * same key, and broadcasts the cancellation if enabled.
     *
     * @return {@code true} if the request was registered, {@code false} if it
     *         cannot be (already cancelled, duplicate, or the previous request
     *         is not cancellable)
     */
    public boolean startRequestAndCancelPrevious(CancellableRequest request) {
        CancellationResult result = registerRequest(request);
        if (result != null && broadcastCancellation) {
            rabbitTemplate.convertAndSend(sharedCancellationExchange, "", result);
        }
        return result != null;
    }

    /** Registers a cancellation result received from another instance. */
    public void registerRemoteCancellation(CancellationResult result) {
        registerRequest(result.toRequest());
    }

    private CancellationResult registerRequest(CancellableRequest request) {
        long now = System.nanoTime();
        Key key = new Key(request.type(), request.scope());
        AtomicReference<UUID> cancelledId = new AtomicReference<>();
        AtomicBoolean registered = new AtomicBoolean(false);

        requests.compute(key, (k, previous) -> {
            if (previous == null) {
                registered.set(true);
                return new Entry(request.requestId(), request.cancellable(), now, null, Map.of());
            }
            if ((!previous.cancellable() && !previous.finishedBefore(now))
                    || previous.activeRequestId().equals(request.requestId())
                    || previous.cancelledIds().containsKey(request.requestId())) {
                registered.set(false);
                return previous;
            }

            cancelledId.set(previous.activeRequestId());
            Map<UUID, Long> cancelledIds = previous.cancelledIds().entrySet().stream()
                    .filter(e -> e.getValue() + requestLifetime.toNanos() >= now)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (!CancellableRequest.isCancellationRequest(previous.activeRequestId())) {
                cancelledIds.put(previous.activeRequestId(), now);
            }
            registered.set(true);
            return new Entry(request.requestId(), request.cancellable(), now, null, cancelledIds);
        });

        if (!registered.get()) {
            return null;
        }
        return new CancellationResult(
                request.type(), request.scope(), request.requestId(), request.cancellable(), cancelledId.get());
    }

    /** Marks {@code request} as finished. */
    public void finishRequest(CancellableRequest request) {
        long now = System.nanoTime();
        Key key = new Key(request.type(), request.scope());
        requests.compute(key, (k, current) ->
                current != null && current.activeRequestId().equals(request.requestId())
                        ? current.finishedAt(now)
                        : current);
    }

    /** Cancels the currently active request for the given type and scope. */
    public void cancelPreviousRequest(CancellableRequestType type, CancellableRequestScope scope) {
        startRequestAndCancelPrevious(CancellableRequest.cancellationRequest(type, scope));
    }

    public boolean isRequestCancelled(CancellableRequest request) {
        Entry entry = requests.get(new Key(request.type(), request.scope()));
        return entry != null && entry.cancelledIds().containsKey(request.requestId());
    }

    private record Key(CancellableRequestType type, CancellableRequestScope scope) {
    }

    private record Entry(
            UUID activeRequestId,
            boolean cancellable,
            long registrationTime,
            Long finishTime,
            Map<UUID, Long> cancelledIds
    ) {
        Entry finishedAt(long time) {
            return new Entry(activeRequestId, cancellable, registrationTime, time, cancelledIds);
        }

        boolean finishedBefore(long time) {
            return finishTime != null && finishTime < time;
        }
    }
}
