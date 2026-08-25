package example.rabbitmq.common.cancel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CancellableRequestHolderTest {

    private SharedCancellationService cancellation;
    private final CancellableRequestType type = CancellableRequestType.COMPILATION;
    private final CancellableRequestScope scope = CancellableRequestScope.of(new ResourceId("source-1"));

    @BeforeEach
    void setUp() {
        cancellation = new SharedCancellationService(mock(RabbitTemplate.class), false);
        ReflectionTestUtils.setField(cancellation, "requestLifetime", Duration.ofMinutes(10));
    }

    private CancellableRequest request(UUID id) {
        return new CancellableRequest(type, scope, id, true);
    }

    @Test
    void doWithNewRequestRunsActionInsideContextAndCleansUp() {
        CancellableRequest request = request(UUID.randomUUID());
        AtomicReference<CancellableRequest> seen = new AtomicReference<>();

        String result = CancellableRequestHolder.doWithNewRequest(cancellation, request,
                () -> {
                    seen.set(CancellableRequestHolder.requireCurrentRequest());
                    return "done";
                });

        assertThat(result).isEqualTo("done");
        assertThat(seen.get()).isEqualTo(request);
        assertThatThrownBy(CancellableRequestHolder::requireCurrentRequest)
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void doWithNewRequestThrowsWhenRegistrationIsRejected() {
        CancellableRequest blocker = new CancellableRequest(type, scope, UUID.randomUUID(), false);
        assertThat(cancellation.startRequestAndCancelPrevious(blocker)).isNotNull();
        CancellableRequest request = request(UUID.randomUUID());

        assertThatThrownBy(() -> CancellableRequestHolder.doWithNewRequest(cancellation, request, () -> "never"))
                .isInstanceOf(RequestCancelledException.class)
                .hasMessageContaining("on-starting-request");
    }

    @Test
    void doWithExistingRequestFinishesRequestOnExit() {
        CancellableRequest request = request(UUID.randomUUID());
        assertThat(cancellation.startRequestAndCancelPrevious(request)).isNotNull();

        CancellableRequestHolder.doWithExistingRequest(cancellation, request, () -> "x");

        CancellableRequest other = request(UUID.randomUUID());
        assertThat(cancellation.startRequestAndCancelPrevious(other)).isNotNull();
    }

    @Test
    void throwIfRequestCancelledThrowsAtCheckpointWhenCancelled() {
        CancellableRequest first = request(UUID.randomUUID());
        assertThat(cancellation.startRequestAndCancelPrevious(first)).isNotNull();
        CancellableRequest second = request(UUID.randomUUID());
        cancellation.startRequestAndCancelPrevious(second);

        assertThatThrownBy(() -> CancellableRequestHolder.doWithExistingRequest(cancellation, first,
                () -> {
                    CancellableRequestHolder.throwIfRequestCancelled("checkpoint-1");
                    return "x";
                }))
                .isInstanceOf(RequestCancelledException.class)
                .hasMessageContaining("checkpoint-1");
    }

    @Test
    void throwIfRequestCancelledPassesWhenNotCancelled() {
        CancellableRequest request = request(UUID.randomUUID());
        assertThat(cancellation.startRequestAndCancelPrevious(request)).isNotNull();

        String result = CancellableRequestHolder.doWithExistingRequest(cancellation, request,
                () -> {
                    CancellableRequestHolder.throwIfRequestCancelled("checkpoint-2");
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void requireCurrentRequestOutsideContextThrows() {
        assertThatThrownBy(CancellableRequestHolder::requireCurrentRequest)
                .isInstanceOf(NullPointerException.class);
    }
}
