package example.rabbitmq.gateway.config;

import example.rabbitmq.gateway.dataprovider.CloudControllerScalingProvider;
import example.rabbitmq.gateway.dataprovider.NoopScalingProvider;
import example.rabbitmq.gateway.dataprovider.ScalingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

@Configuration
@EnableScheduling
public class GatewayConfiguration {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "example.scaling.provider", havingValue = "cloud-controller")
    public ScalingProvider cloudControllerScalingProvider(RestTemplate restTemplate,
            @Value("${example.scaling.controller-url}") String controllerUrl) {
        return new CloudControllerScalingProvider(restTemplate, controllerUrl);
    }

    @Bean
    @ConditionalOnMissingBean(ScalingProvider.class)
    public ScalingProvider noopScalingProvider() {
        return new NoopScalingProvider();
    }
}
