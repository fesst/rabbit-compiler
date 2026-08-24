package example.rabbitmq.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Periodically re-evaluates every {@link ScalingService}. */
@Component
public class ScalingManagementJob {

    private static final Logger log = LoggerFactory.getLogger(ScalingManagementJob.class);

    private final List<ScalingService> scalingServices;

    public ScalingManagementJob(List<ScalingService> scalingServices) {
        this.scalingServices = scalingServices;
        log.info("Scaling management job started with {} services", scalingServices.size());
    }

    @Scheduled(fixedDelayString = "${example.scaling.management-job.delay-ms:30000}")
    public void execute() {
        for (ScalingService service : scalingServices) {
            service.run();
        }
    }
}
