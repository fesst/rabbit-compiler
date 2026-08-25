package example.rabbitmq.worker;

import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CompletionWorkerTest {

    private RabbitTemplate rabbitTemplate;
    private CompletionWorker worker;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        worker = new CompletionWorker(rabbitTemplate);
    }

    private CompletionResultDto run(String text) {
        worker.onCompletionRequest(new CompletionRequestDto("A.java", 1, 1, text),
                new Message(new byte[0], props("reply.q", "corr-1")));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(""), eq("reply.q"), payload.capture(), any(MessagePostProcessor.class));
        return (CompletionResultDto) payload.getValue();
    }

    private static MessageProperties props(String replyTo, String correlationId) {
        MessageProperties p = new MessageProperties();
        p.setReplyTo(replyTo);
        p.setCorrelationId(correlationId);
        return p;
    }

    @Test
    void extractsUniqueWordsFromText() {
        CompletionResultDto result = run("foo bar foo baz qux");
        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).containsExactlyInAnyOrder("foo", "bar", "baz", "qux");
    }

    @Test
    void capsSuggestionsAtTwenty() {
        String text = String.join(" ", IntStream.range(0, 50).mapToObj(i -> "word" + i).toList());
        assertThat(run(text).suggestions()).hasSize(20);
    }

    @Test
    void emptyTextReturnsNoSuggestions() {
        assertThat(run("").suggestions()).isEmpty();
    }

    @Test
    void nullTextIsTreatedAsEmpty() {
        assertThat(run(null).suggestions()).isEmpty();
    }

    @Test
    void singleCharacterTokensAreNotSuggested() {
        assertThat(run("a b c xyz").suggestions()).containsExactly("xyz");
    }

    @Test
    void replyCarriesCorrelationId() {
        worker.onCompletionRequest(new CompletionRequestDto("A.java", 1, 1, "text"),
                new Message(new byte[0], props("reply.q", "corr-9")));
        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(""), eq("reply.q"), (Object) any(CompletionResultDto.class), mpp.capture());
        Message reply = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(reply.getMessageProperties().getCorrelationId()).isEqualTo("corr-9");
    }

    @Test
    void missingReplyToDropsResult() {
        worker.onCompletionRequest(new CompletionRequestDto("A.java", 1, 1, "text"),
                new Message(new byte[0], props(null, "corr-1")));
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }
}
