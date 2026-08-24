package example.rabbitmq.sourcechanger.config;

import example.rabbitmq.common.cancel.SharedCancellationService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the shared cancellation service. */
@Configuration
public class RequestCancellationConfig {

  @Bean
  public SharedCancellationService cancellationService(
      @Qualifier("sharedCancellationRabbitTemplate") RabbitTemplate sharedCancellationRabbitTemplate) {
    return new SharedCancellationService(sharedCancellationRabbitTemplate, true);
  }
}
