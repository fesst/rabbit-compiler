package example.rabbitmq.sourcechanger.mq;

import example.rabbitmq.sourcechanger.config.ServiceConfig;
import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.exception.MessageSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.AsyncRabbitTemplate;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompletionServiceTest {

    private AsyncRabbitTemplate template;
    private CompletionService service;

    @BeforeEach
    void setUp() {
        template = mock(AsyncRabbitTemplate.class);
        service = new CompletionService(mock(ServiceConfig.class), template);
    }

    @SuppressWarnings("unchecked")
    private void stubReply(CompletionResultDto result) {
        AsyncRabbitTemplate.RabbitConverterFuture<CompletionResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenReturn(result);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompletionResultDto>>any())).thenReturn(future);
    }

    @Test
    void forwardsRequestAndReturnsSuggestions() {
        stubReply(new CompletionResultDto(true, "ok", List.of("alpha", "beta")));

        CompletionResultDto result = service.complete(
                new CompletionRequestDto("src/A.java", 3, 7, "class A {}"));

        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).containsExactly("alpha", "beta");
        verify(template).convertSendAndReceiveAsType(any(), any(), any(), any(), any());
    }

    @Test
    void failureResultIsReturnedAsIs() {
        stubReply(new CompletionResultDto(false, "no context", List.of()));

        CompletionResultDto result = service.complete(
                new CompletionRequestDto("src/A.java", 1, 1, ""));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("no context");
    }

    @Test
    void interruptedReplyRaisesMessageSendExceptionAndRestoresInterruptFlag() {
        @SuppressWarnings("unchecked")
        AsyncRabbitTemplate.RabbitConverterFuture<CompletionResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenThrow(new InterruptedException("interrupted"));
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompletionResultDto>>any())).thenReturn(future);

        assertThatThrownBy(() -> service.complete(new CompletionRequestDto("src/A.java", 1, 1, "")))
                .isInstanceOf(MessageSendException.class)
                .hasRootCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void replyFailureRaisesMessageSendException() {
        @SuppressWarnings("unchecked")
        AsyncRabbitTemplate.RabbitConverterFuture<CompletionResultDto> future =
                mock(AsyncRabbitTemplate.RabbitConverterFuture.class);
        try {
            when(future.get()).thenThrow(new ExecutionException(new RuntimeException("down")));
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
        when(template.convertSendAndReceiveAsType(
                Mockito.<String>any(), Mockito.<String>any(), Mockito.any(),
                Mockito.any(MessagePostProcessor.class),
                Mockito.<ParameterizedTypeReference<CompletionResultDto>>any())).thenReturn(future);

        assertThatThrownBy(() -> service.complete(new CompletionRequestDto("src/A.java", 1, 1, "")))
                .isInstanceOf(MessageSendException.class)
                .hasRootCauseInstanceOf(RuntimeException.class);
    }
}
