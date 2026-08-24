package example.rabbitmq.sourcechanger.mq;

import example.rabbitmq.common.cancel.CancellableRequest;
import example.rabbitmq.common.cancel.CancellableRequestHolder;
import example.rabbitmq.sourcechanger.config.ServiceConfig;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.exception.MessageSendException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.AsyncRabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * Sends a compilation request to a worker service over RabbitMQ and waits for
 * the result. Runs inside a {@link CancellableRequestHolder} context so a
 * concurrent cancellation interrupts it.
 */
@Component
public class CompilationService {

  private static final int MAINTENANCE_RETRIES = 3;

  private final ServiceConfig serviceConfig;
  private final AsyncRabbitTemplate compilationRabbitTemplate;

  public CompilationService(
      ServiceConfig serviceConfig,
      @Qualifier("compilationRabbitTemplate") AsyncRabbitTemplate compilationRabbitTemplate) {
    this.serviceConfig = serviceConfig;
    this.compilationRabbitTemplate = compilationRabbitTemplate;
  }

  public CompilationResultDto compile() {
    CancellableRequest request = CancellableRequestHolder.requireCurrentRequest();

    for (int attempt = 0; attempt < MAINTENANCE_RETRIES; attempt++) {
      CancellableRequestHolder.throwIfRequestCancelled("before-compilation-send");
      CompilationResultDto result = sendAndReceive(request);
      if (result.resultType() != CompilationResultDto.ResultType.MAINTENANCE) {
        return result;
      }
    }
    return new CompilationResultDto(false, CompilationResultDto.ResultType.MAINTENANCE, "retries exhausted");
  }

  private CompilationResultDto sendAndReceive(CancellableRequest request) {
    AsyncRabbitTemplate.RabbitConverterFuture<CompilationResultDto> future = compilationRabbitTemplate
        .convertSendAndReceiveAsType(
            serviceConfig.getRequestCompilationQueue(),
            serviceConfig.getRoutingKey(),
            request,
            this::decorate,
            new ParameterizedTypeReference<>() {
            });

    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MessageSendException("Compilation request interrupted", e);
    } catch (ExecutionException e) {
      throw new MessageSendException("Compilation request failed", e);
    }
  }

  private Message decorate(Message message) {
    message.getMessageProperties().setCorrelationId(UUID.randomUUID().toString());
    message.getMessageProperties().setType(CompilationResultDto.class.getSimpleName());
    return message;
  }
}
