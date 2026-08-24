package example.rabbitmq.sourcechanger.mq;

import example.rabbitmq.common.cancel.CancellableRequest;
import example.rabbitmq.common.cancel.CancellableRequestHolder;
import example.rabbitmq.common.cancel.CancellableRequestScope;
import example.rabbitmq.common.cancel.CancellableRequestType;
import example.rabbitmq.common.cancel.ResourceId;
import example.rabbitmq.common.cancel.SharedCancellationService;
import example.rabbitmq.sourcechanger.config.ServiceConfig;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.exception.MessageSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.AsyncRabbitTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.ParameterizedTypeReference;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompilationServiceTest {

    private ServiceConfig config;
    private AsyncRabbitTemplate template;
    private SharedCancellationService cancellation;
    private CompilationService service;
    private CancellableRequest request;

    @BeforeEach
    void setUp() {
        config = mock(ServiceConfig.class);
        template = mock(AsyncRabbitTemplate.class);
        cancellation = new SharedCancellationService(mock(RabbitTemplate.class), false);
        service = new CompilationService(config, template);
        request = new CancellableRequest(
                CancellableRequestType.COMPILATION,
                CancellableRequestScope.of(new ResourceId("source-1")),
                UUID.randomUUID(),
                true);
    }

    @SuppressWarnings("unchecked")
    private AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> stubReply(CompilationResultDto result) {
        AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenReturn(result);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompilationResultDto>>any())).thenReturn(future);
        return future;
    }

    @SuppressWarnings("unchecked")
    private void stubFailure(Throwable cause) {
        AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenThrow(new ExecutionException(cause));
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompilationResultDto>>any())).thenReturn(future);
    }

    @Test
    void returnsSuccessfulResult() {
        stubReply(new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "ok"));

        CompilationResultDto result = CancellableRequestHolder.doWithNewRequest(cancellation, request, service::compile);

        assertThat(result.success()).isTrue();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.SUCCESS);
        assertThat(result.message()).isEqualTo("ok");
    }

    @Test
    void returnsFailureResultWithoutRetrying() {
        stubReply(new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, "boom"));

        CompilationResultDto result = CancellableRequestHolder.doWithNewRequest(cancellation, request, service::compile);

        assertThat(result.success()).isFalse();
        verify(template, times(1)).convertSendAndReceiveAsType(any(), any(), any(), any(), any());
    }

    @Test
    void maintenanceResultIsRetriedThenExhausted() {
        stubReply(new CompilationResultDto(false, CompilationResultDto.ResultType.MAINTENANCE, "busy"));

        CompilationResultDto result = CancellableRequestHolder.doWithNewRequest(cancellation, request, service::compile);

        assertThat(result.success()).isFalse();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.MAINTENANCE);
        assertThat(result.message()).isEqualTo("retries exhausted");
        verify(template, times(3)).convertSendAndReceiveAsType(any(), any(), any(), any(), any());
    }

    @Test
    void replyFailureRaisesMessageSendException() {
        stubFailure(new RuntimeException("nack"));

        assertThatThrownBy(() -> CancellableRequestHolder.doWithNewRequest(cancellation, request, service::compile))
                .isInstanceOf(MessageSendException.class)
                .hasRootCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void interruptedReplyRaisesMessageSendExceptionAndRestoresInterruptFlag() {
        @SuppressWarnings("unchecked")
        AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenThrow(new InterruptedException("interrupted"));
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompilationResultDto>>any())).thenReturn(future);

        assertThatThrownBy(() -> CancellableRequestHolder.doWithNewRequest(cancellation, request, service::compile))
                .isInstanceOf(MessageSendException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void compileRequiresACancellableRequestContext() {
        stubReply(new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "ok"));

        assertThatThrownBy(service::compile).isInstanceOf(NullPointerException.class);
    }
}
