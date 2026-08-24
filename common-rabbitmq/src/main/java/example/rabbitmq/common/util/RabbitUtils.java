package example.rabbitmq.common.util;

import org.springframework.amqp.core.Queue;

/** Queue naming and creation helpers. */
public final class RabbitUtils {

    private RabbitUtils() {
    }

    /** Unique per-instance queue name. */
    public static String dedicatedQueueName(String baseQueueName) {
        return baseQueueName + "_" + System.getProperty("example.instance-id", "instance");
    }

    /** Queue name shared by all common (non-dedicated) clients. */
    public static String commonQueueName(String baseQueueName) {
        return baseQueueName + "_common";
    }

    /**
     * Creates a queue that is durable (survives a broker restart), non-exclusive
     * (shared across connections) and auto-deleted (removed once all consumers
     * stop, since names are unique per instance).
     */
    public static Queue createQueue(String queueName) {
        return new Queue(queueName, true, false, true);
    }
}
