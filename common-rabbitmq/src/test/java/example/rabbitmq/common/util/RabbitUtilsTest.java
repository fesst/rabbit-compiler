package example.rabbitmq.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitUtilsTest {

    @Test
    void dedicatedQueueNameUsesDefaultInstanceId() {
        String previous = System.getProperty("example.instance-id");
        System.clearProperty("example.instance-id");
        try {
            assertThat(RabbitUtils.dedicatedQueueName("sharedCancellationQueue"))
                    .isEqualTo("sharedCancellationQueue_instance");
        } finally {
            restore(previous);
        }
    }

    @Test
    void dedicatedQueueNameHonorsInstanceIdProperty() {
        String previous = System.getProperty("example.instance-id");
        System.setProperty("example.instance-id", "node-7");
        try {
            assertThat(RabbitUtils.dedicatedQueueName("sharedCancellationQueue"))
                    .isEqualTo("sharedCancellationQueue_node-7");
        } finally {
            restore(previous);
        }
    }

    @Test
    void commonQueueNameAppendsCommon() {
        assertThat(RabbitUtils.commonQueueName("requestCompilation")).isEqualTo("requestCompilation_common");
    }

    @Test
    void createQueueIsDurableSharedAndAutoDeleted() {
        org.springframework.amqp.core.Queue queue = RabbitUtils.createQueue("demo");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isTrue();
    }

    private static void restore(String previous) {
        if (previous != null) {
            System.setProperty("example.instance-id", previous);
        } else {
            System.clearProperty("example.instance-id");
        }
    }
}
