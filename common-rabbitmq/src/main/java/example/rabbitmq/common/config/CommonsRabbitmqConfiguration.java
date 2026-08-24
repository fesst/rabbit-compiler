package example.rabbitmq.common.config;

import example.rabbitmq.common.cancel.SharedCancellationRabbitListener;
import example.rabbitmq.common.util.RabbitUtils;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Shared RabbitMQ configuration for the cancellation channel.
 *
 * <p>Each instance declares its own auto-delete queue bound to a shared direct
 * exchange, so every instance receives every cancellation broadcast.
 */
@Configuration("commonsRabbitmqConfiguration")
@ComponentScan("example.rabbitmq.common")
public class CommonsRabbitmqConfiguration {

    @Value("${example.mq.shared-cancellation.exchange}")
    private String sharedCancellationExchangeName;

    @Value("${example.mq.shared-cancellation.queue-prefix}")
    private String sharedCancellationQueuePrefix;

    public String sharedCancellationExchangeName() {
        return sharedCancellationExchangeName;
    }

    public String sharedCancellationQueueName() {
        return RabbitUtils.dedicatedQueueName(sharedCancellationQueuePrefix);
    }

    @Bean
    public RabbitTemplate sharedCancellationRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }

    @Bean
    public DirectRabbitListenerContainerFactory sharedCancellationRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        DirectRabbitListenerContainerFactory factory = new DirectRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        return factory;
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange sharedCancellationExchange() {
        return new DirectExchange(sharedCancellationExchangeName, true, false);
    }

    @Bean
    @ConditionalOnBean(SharedCancellationRabbitListener.class)
    public Queue sharedCancellationQueue() {
        return RabbitUtils.createQueue(sharedCancellationQueueName());
    }

    @Bean
    @ConditionalOnBean(SharedCancellationRabbitListener.class)
    public Binding sharedCancellationBinding() {
        return BindingBuilder.bind(sharedCancellationQueue()).to(sharedCancellationExchange()).with("");
    }
}
