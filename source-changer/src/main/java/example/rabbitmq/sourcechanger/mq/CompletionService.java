package example.rabbitmq.sourcechanger.mq;

import example.rabbitmq.sourcechanger.config.ServiceConfig;
import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.exception.MessageSendException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.AsyncRabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Sends a code-completion request to a worker service over RabbitMQ and waits
 * for the result.
 */
@Component
public class CompletionService {

  private final ServiceConfig serviceConfig;
  private final AsyncRabbitTemplate completionRabbitTemplate;

  public CompletionService(
      ServiceConfig serviceConfig,
      @Qualifier("completionRabbitTemplate") AsyncRabbitTemplate completionRabbitTemplate) {
    this.serviceConfig = serviceConfig;
    this.completionRabbitTemplate = completionRabbitTemplate;
  }

  public CompletionResultDto complete(CompletionRequestDto request) {
    return sendAndReceive(request);
  }

  private CompletionResultDto sendAndReceive(CompletionRequestDto request) {
    AsyncRabbitTemplate.RabbitConverterFuture<CompletionResultDto> future = completionRabbitTemplate
        .convertSendAndReceiveAsType(
            serviceConfig.getRequestCompletionQueue(),
            serviceConfig.getRoutingKey(),
            request,
            this::decorate,
            new ParameterizedTypeReference<>() {
            });

    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MessageSendException("Completion request interrupted", e);
    } catch (ExecutionException e) {
      throw new MessageSendException("Completion request failed", e);
    }
  }

  private Message decorate(Message message) {
    message.getMessageProperties().setCorrelationId(UUID.randomUUID().toString());
    message.getMessageProperties().setType(CompletionResultDto.class.getSimpleName());
    return message;
  }
}
