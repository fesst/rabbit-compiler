package example.rabbitmq.common.cancel;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Receives cancellations broadcast by other instances and applies them to the
 * local {@link SharedCancellationService}.
 */
@Component
public class SharedCancellationRabbitListener {

    private final SharedCancellationService cancellationService;

    public SharedCancellationRabbitListener(SharedCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @RabbitListener(
            queues = "#{@commonsRabbitmqConfiguration.sharedCancellationQueueName()}",
            containerFactory = "sharedCancellationRabbitListenerContainerFactory"
    )
    public void receiveCancellation(CancellationResult result) {
        cancellationService.registerRemoteCancellation(result);
    }
}
