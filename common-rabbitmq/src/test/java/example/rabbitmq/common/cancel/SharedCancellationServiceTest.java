package example.rabbitmq.common.cancel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Edge cases of the (type, scope) request/cancellation state machine. */
class SharedCancellationServiceTest {

    private RabbitTemplate rabbitTemplate;
    private SharedCancellationService service;
    private final CancellableRequestType type = CancellableRequestType.COMPILATION;
    private final CancellableRequestScope scope = CancellableRequestScope.of(new ResourceId("source-1"));

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new SharedCancellationService(rabbitTemplate, true);
        ReflectionTestUtils.setField(service, "requestLifetime", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "sharedCancellationExchange", "sharedCancellationExchange");
    }

    private CancellableRequest request(UUID id, boolean cancellable) {
        return new CancellableRequest(type, scope, id, cancellable);
    }

    /** The last CancellationResult the service broadcast to the broker. */
    private CancellationResult lastBroadcast() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce())
                .convertAndSend(eq("sharedCancellationExchange"), eq(""), captor.capture());
        java.util.List<Object> values = captor.getAllValues();
        return (CancellationResult) values.get(values.size() - 1);
    }

    @Test
    void firstRequestRegistersAndFinishingDoesNotCancelIt() {
        UUID id = UUID.randomUUID();
        assertThat(service.startRequestAndCancelPrevious(request(id, true))).isTrue();
        assertThat(service.isRequestCancelled(request(id, true))).isFalse();
        service.finishRequest(request(id, true));
        assertThat(service.isRequestCancelled(request(id, true))).isFalse();
    }

    @Test
    void duplicateRequestIdIsRejected() {
        UUID id = UUID.randomUUID();
        assertThat(service.startRequestAndCancelPrevious(request(id, true))).isTrue();
        assertThat(service.startRequestAndCancelPrevious(request(id, true))).isFalse();
    }

    @Test
    void newRequestCancelsPreviousAndRecordsIt() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(service.startRequestAndCancelPrevious(request(first, true))).isTrue();
        assertThat(lastBroadcast().cancelledRequestId()).isNull();

        assertThat(service.startRequestAndCancelPrevious(request(second, true))).isTrue();
        assertThat(lastBroadcast().cancelledRequestId()).isEqualTo(first);
        assertThat(service.isRequestCancelled(request(first, true))).isTrue();
        assertThat(service.isRequestCancelled(request(second, true))).isFalse();
    }

    @Test
    void nonCancellablePreviousBlocksNewRequest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(service.startRequestAndCancelPrevious(request(first, false))).isTrue();
        assertThat(service.startRequestAndCancelPrevious(request(second, true))).isFalse();
        assertThat(service.isRequestCancelled(request(second, true))).isFalse();
    }

    @Test
    void previouslyCancelledRequestCannotRegisterAgain() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(first, true));
        service.startRequestAndCancelPrevious(request(second, true));
        assertThat(service.startRequestAndCancelPrevious(request(first, true))).isFalse();
    }

    @Test
    void cancellationCommandCancelsActiveRequest() {
        UUID first = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(first, true));
        assertThat(service.startRequestAndCancelPrevious(
                CancellableRequest.cancellationRequest(type, scope))).isTrue();
        assertThat(lastBroadcast().cancelledRequestId()).isEqualTo(first);
        assertThat(service.isRequestCancelled(request(first, true))).isTrue();
    }

    @Test
    void cancellationCommandOnEmptyKeyRegistersWithoutCancellingAnyone() {
        assertThat(service.startRequestAndCancelPrevious(
                CancellableRequest.cancellationRequest(type, scope))).isTrue();
        assertThat(lastBroadcast().cancelledRequestId()).isNull();
    }

    @Test
    void finishedNonCancellableRequestAllowsNewRegistration() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(first, false));
        service.finishRequest(request(first, false));
        assertThat(service.startRequestAndCancelPrevious(request(second, true))).isTrue();
    }

    @Test
    void finishedCancellableRequestCanBeSuperseded() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(first, true));
        service.finishRequest(request(first, true));
        assertThat(service.startRequestAndCancelPrevious(request(second, true))).isTrue();
    }

    @Test
    void finishRequestWithWrongIdIsNoop() {
        UUID id = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(id, true));
        service.finishRequest(request(UUID.randomUUID(), true));
        assertThat(service.startRequestAndCancelPrevious(request(id, true))).isFalse();
    }

    @Test
    void isRequestCancelledForUnknownKeyIsFalse() {
        assertThat(service.isRequestCancelled(request(UUID.randomUUID(), true))).isFalse();
    }

    @Test
    void broadcastSendsCancellationResultWhenEnabled() {
        service.startRequestAndCancelPrevious(request(UUID.randomUUID(), true));
        verify(rabbitTemplate).convertAndSend(eq("sharedCancellationExchange"), eq(""), any(CancellationResult.class));
    }

    @Test
    void broadcastDisabledDoesNotSend() {
        SharedCancellationService silent = new SharedCancellationService(rabbitTemplate, false);
        ReflectionTestUtils.setField(silent, "requestLifetime", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(silent, "sharedCancellationExchange", "sharedCancellationExchange");
        silent.startRequestAndCancelPrevious(request(UUID.randomUUID(), true));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void registerRemoteCancellationCancelsTheLocallyActiveRequest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.startRequestAndCancelPrevious(request(first, true));
        service.startRequestAndCancelPrevious(request(second, true));
        CancellationResult broadcast = lastBroadcast();
        assertThat(broadcast.cancelledRequestId()).isEqualTo(first);

        SharedCancellationService other = new SharedCancellationService(mock(RabbitTemplate.class), false);
        ReflectionTestUtils.setField(other, "requestLifetime", Duration.ofMinutes(10));
        other.startRequestAndCancelPrevious(request(first, true));
        other.registerRemoteCancellation(broadcast);
        assertThat(other.isRequestCancelled(request(first, true))).isTrue();
    }

    @Test
    void cleanupRemovesExpiredEntries() throws Exception {
        SharedCancellationService shortLived = new SharedCancellationService(mock(RabbitTemplate.class), false);
        ReflectionTestUtils.setField(shortLived, "requestLifetime", Duration.ofMillis(1));
        UUID id = UUID.randomUUID();
        shortLived.startRequestAndCancelPrevious(request(id, true));
        Thread.sleep(20);
        shortLived.cleanup();
        assertThat(shortLived.isRequestCancelled(request(id, true))).isFalse();
        assertThat(shortLived.startRequestAndCancelPrevious(request(id, true))).isTrue();
    }
}
