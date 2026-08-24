package example.rabbitmq.gateway.service;

import example.rabbitmq.gateway.model.InstanceData;
import org.springframework.stereotype.Component;

/**
 * Holds the latest cluster snapshot. The snapshot is refreshed by a metrics
 * collector; only the holder is shown here.
 */
@Component
public class InstanceRegistry {

    private volatile InstanceData data = InstanceData.empty();

    public InstanceData getData() {
        return data;
    }

    public void setData(InstanceData data) {
        this.data = data;
    }
}
