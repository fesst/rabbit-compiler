package example.rabbitmq.worker;

import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumes completion requests and answers with identifiers found in the
 * request text (stub completion, mirroring the local worker behaviour).
 */
@Component
public class CompletionWorker {

  private static final Logger log = LoggerFactory.getLogger(CompletionWorker.class);
  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,}");
  private static final int MAX_SUGGESTIONS = 20;

  private final RabbitTemplate rabbitTemplate;

  public CompletionWorker(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  @RabbitListener(queues = "${example.mq.queue.completion.request:requestCompletion}")
  public void onCompletionRequest(CompletionRequestDto request, Message message) {
    log.info("completion request received: resourceId={}", request.resourceId());
    Set<String> words = new LinkedHashSet<>();
    Matcher matcher = WORD.matcher(request.text() == null ? "" : request.text());
    while (matcher.find() && words.size() < MAX_SUGGESTIONS) {
      words.add(matcher.group());
    }
    CompletionResultDto result = new CompletionResultDto(true, "local completion", List.copyOf(words));
    reply(message, result);
  }

  private void reply(Message request, Object payload) {
    String replyTo = request.getMessageProperties().getReplyTo();
    if (replyTo == null) {
      log.warn("no replyTo on request, result dropped");
      return;
    }
    String correlationId = request.getMessageProperties().getCorrelationId();
    rabbitTemplate.convertAndSend("", replyTo, payload, m -> {
      if (correlationId != null) {
        m.getMessageProperties().setCorrelationId(correlationId);
      }
      return m;
    });
  }
}
