package example.rabbitmq.gateway.dataprovider;

/** Performs the actual scale-up / scale-down against the infrastructure. */
public interface ScalingProvider {

    boolean scaleUp(String templateName);

    boolean scaleDown(String templateName, String nodeId);
}
