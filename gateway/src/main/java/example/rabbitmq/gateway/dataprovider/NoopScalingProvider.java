package example.rabbitmq.gateway.dataprovider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ScalingProvider} that does nothing; used when scaling is disabled or
 * during local development.
 */
public class NoopScalingProvider implements ScalingProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopScalingProvider.class);

    @Override
    public boolean scaleUp(String templateName) {
        log.info("Scale-up of template '{}' requested (no-op)", templateName);
        return false;
    }

    @Override
    public boolean scaleDown(String templateName, String nodeId) {
        log.info("Scale-down of template '{}' node '{}' requested (no-op)", templateName, nodeId);
        return false;
    }
}
