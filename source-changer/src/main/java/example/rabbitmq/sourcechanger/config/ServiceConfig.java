package example.rabbitmq.sourcechanger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ exchange names and routing key used by the request/reply services.
 */
@Component
public class ServiceConfig {

  @Value("${example.mq.queue.compilation.request}")
  private String requestCompilationQueue;

  @Value("${example.mq.queue.completion.request}")
  private String requestCompletionQueue;

  /**
   * Routing key for request/reply calls. An instance dedicated to a single
   * user uses that user's id as the key so replies route to its own queue; a
   * common instance uses an empty key and listens on the shared queue.
   */
  @Value("${example.mq.routing-key:}")
  private String routingKey;

  public String getRequestCompilationQueue() {
    return requestCompilationQueue;
  }

  public String getRequestCompletionQueue() {
    return requestCompletionQueue;
  }

  public String getRoutingKey() {
    return routingKey;
  }
}
