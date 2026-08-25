package example.rabbitmq.worker;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the request/reply topology the source-changer publishes to: a
 * durable direct exchange per channel, a durable shared queue bound with the
 * common routing key (""), and the JSON message converter used by both the
 * listener containers and the reply template.
 */
@Configuration
public class RabbitWorkerConfig {

  @Value("${example.mq.queue.compilation.request}")
  private String compilationQueue;

  @Value("${example.mq.queue.completion.request}")
  private String completionQueue;

  @Value("${example.mq.routing-key:}")
  private String routingKey;

  @Bean
  public DirectExchange compilationExchange() {
    return new DirectExchange(compilationQueue, true, false);
  }

  @Bean
  public DirectExchange completionExchange() {
    return new DirectExchange(completionQueue, true, false);
  }

  @Bean
  public Queue compilationQueueBean() {
    return new Queue(compilationQueue, true);
  }

  @Bean
  public Queue completionQueueBean() {
    return new Queue(completionQueue, true);
  }

  @Bean
  public Binding compilationBinding() {
    return BindingBuilder.bind(compilationQueueBean()).to(compilationExchange()).with(routingKey);
  }

  @Bean
  public Binding completionBinding() {
    return BindingBuilder.bind(completionQueueBean()).to(completionExchange()).with(routingKey);
  }

  @Bean
  public Jackson2JsonMessageConverter workerJsonConverter() {
    // trusted packages are constructor args in spring-amqp 2.4
    // (java.util/java.lang are always trusted by the type mapper)
    return new Jackson2JsonMessageConverter("example.rabbitmq");
  }
}
