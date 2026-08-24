package example.rabbitmq.gateway.dataprovider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * {@link ScalingProvider} backed by the infrastructure controller REST API.
 */
public class CloudControllerScalingProvider implements ScalingProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudControllerScalingProvider.class);
    private static final String SUCCESS = "SUCCESS";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CloudControllerScalingProvider(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean scaleUp(String templateName) {
        return post("/template/" + templateName + "/scaleup");
    }

    @Override
    public boolean scaleDown(String templateName, String nodeId) {
        return post("/template/" + templateName + "/stop/" + nodeId);
    }

    private boolean post(String path) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(baseUrl + path, null, String.class);
            return response.getBody() != null && response.getBody().contains(SUCCESS);
        } catch (Exception e) {
            log.error("Scaling request to '{}' failed", baseUrl + path, e);
            return false;
        }
    }
}
