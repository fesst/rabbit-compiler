package example.rabbitmq.sourcechanger.config;

import org.springframework.amqp.rabbit.AsyncRabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ connection and request/reply templates.
 *
 * <p>
 * One {@link AsyncRabbitTemplate} is declared per channel so each kind of
 * request can have its own receive timeout.
 */
@Configuration
public class RabbitConfig {

  @Value("${spring.rabbitmq.host}")
  private String rabbitHost;

  @Value("${spring.rabbitmq.virtual-host}")
  private String rabbitVhost;

  @Value("${example.compilation-timeout:600000}")
  private long compilationTimeout;

  @Value("${example.completion-timeout:30000}")
  private long completionTimeout;

  @Bean
  public ConnectionFactory connectionFactory() {
    CachingConnectionFactory factory = new CachingConnectionFactory();
    factory.setHost(rabbitHost);
    factory.setVirtualHost(rabbitVhost);
    return factory;
  }

  @Bean
  public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
    return new RabbitAdmin(connectionFactory);
  }

  @Bean
  public AsyncRabbitTemplate compilationRabbitTemplate(ConnectionFactory connectionFactory) {
    return asyncTemplate(connectionFactory, compilationTimeout);
  }

  @Bean
  public AsyncRabbitTemplate completionRabbitTemplate(ConnectionFactory connectionFactory) {
    return asyncTemplate(connectionFactory, completionTimeout);
  }

  private AsyncRabbitTemplate asyncTemplate(ConnectionFactory connectionFactory, long receiveTimeout) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(new Jackson2JsonMessageConverter());
    AsyncRabbitTemplate async = new AsyncRabbitTemplate(template);
    async.setReceiveTimeout(receiveTimeout);
    return async;
  }
}
