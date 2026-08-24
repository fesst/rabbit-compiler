package example.rabbitmq.sourcechanger.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import example.rabbitmq.common.cancel.SharedCancellationService;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.mq.CompletionService;
import example.rabbitmq.sourcechanger.mq.CompilationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * API contract test, independent of the frontend: uploads a zip over REST and
 * drives the WebSocket workspace protocol exactly like the web UI does. If the
 * UI breaks while this test stays green, the problem is frontend-only; if this
 * test breaks, the backend contract changed.
 *
 * <p>RabbitMQ-backed services are mocked so the test runs without a broker;
 * the REST/WS layer itself is exercised for real.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.main.allow-bean-definition-overriding=true"
        })
class WorkspaceApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void workspaceDir(DynamicPropertyRegistry registry) {
        registry.add("example.workspace.dir", tempDir::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ServletServerContainerFactoryBean webSocketContainer;

    @MockBean
    CompilationService compilationService;

    @MockBean
    CompletionService completionService;

    @MockBean
    SharedCancellationService cancellationService;

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private WebSocket socket;

    @BeforeEach
    void stubRabbitBackedServices() {
        when(cancellationService.startRequestAndCancelPrevious(any())).thenReturn(true);
        when(compilationService.compile()).thenReturn(
                new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "compiled in test"));
        when(completionService.complete(any())).thenReturn(
                new CompletionResultDto(true, "ok", List.of("alpha", "beta")));
    }

    @Test
    void uploadThenWorkspaceOverWebSocket() throws Exception {
        // 1. REST: upload the zip
        ResponseEntity<Map> upload = rest.postForEntity(
                "/api/workspaces",
                new HttpEntity<>(multipartZip(), multipartHeaders()),
                Map.class);
        assertThat(upload.getStatusCode().is2xxSuccessful()).isTrue();
        String workspaceId = (String) upload.getBody().get("workspaceId");
        assertThat(workspaceId).isNotBlank();

        // 2. WebSocket: subscribe and walk the whole workspace lifecycle
        openSocket(workspaceId);
        try {
            awaitType("tree");
            sendJson(Map.of("type", "file", "path", "src/main/java/Demo.java"));
            JsonNode fileContent = awaitType("fileContent");
            assertThat(fileContent.path("content").asText()).contains("public class Demo");

            String edited = fileContent.path("content").asText() + "\n// api-test edit";
            sendJson(Map.of("type", "save", "path", "src/main/java/Demo.java", "content", edited));
            awaitType("saved");
            assertThat(Files.readString(tempDir.resolve(workspaceId).resolve("src/main/java/Demo.java")))
                    .endsWith("// api-test edit");

            sendJson(Map.of("type", "compile", "requestId", "c1"));
            JsonNode compile = awaitType("compileResult");
            assertThat(compile.path("success").asBoolean()).isTrue();
            assertThat(compile.path("requestId").asText()).isEqualTo("c1");
            assertThat(compile.path("message").asText()).contains("compiled in test");

            sendJson(Map.of("type", "complete", "requestId", "q1", "path", "src/main/java/Demo.java",
                    "line", 1, "column", 1, "text", ""));
            JsonNode complete = awaitType("completeResult");
            assertThat(complete.path("success").asBoolean()).isTrue();
            assertThat(complete.path("requestId").asText()).isEqualTo("q1");
            assertThat(complete.path("suggestions")).hasSize(2);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    void webSocketContainerBuffersWholeFiles() {
        // Regression: the default 8 KB text buffer made the server drop the
        // connection on any message carrying a whole file (e.g. completion).
        assertThat(webSocketContainer.getMaxTextMessageBufferSize())
                .isEqualTo(WebSocketConfig.MAX_TEXT_MESSAGE_BYTES);
        assertThat(webSocketContainer.getMaxBinaryMessageBufferSize())
                .isEqualTo(WebSocketConfig.MAX_TEXT_MESSAGE_BYTES);
    }

    @Test
    void subscribeToUnknownWorkspaceReturnsError() throws Exception {
        openSocket("does-not-exist");
        try {
            JsonNode error = awaitType("error");
            assertThat(error.path("message").asText()).contains("Unknown workspace");
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    // ---- helpers ----

    private MultiValueMap<String, Object> multipartZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("src/main/java/Demo.java"));
            zip.write("package demo;\npublic class Demo {}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("config.properties"));
            zip.write("hello=world".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes.toByteArray()) {
            @Override
            public String getFilename() {
                return "sources.zip";
            }
        });
        return body;
    }

    private HttpHeaders multipartHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private void openSocket(String workspaceId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Builder builder = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        socket = builder.buildAsync(
                        URI.create("ws://localhost:" + port + "/ws/workspace"),
                        new Listener())
                .join();
        sendJson(Map.of("type", "subscribe", "workspaceId", workspaceId));
    }

    private void sendJson(Map<String, Object> payload) throws Exception {
        socket.sendText(mapper.writeValueAsString(payload), true).join();
    }

    private JsonNode awaitType(String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String payload = received.poll(500, TimeUnit.MILLISECONDS);
            if (payload == null) {
                continue;
            }
            JsonNode node = mapper.readTree(payload);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("No message of type '" + type + "' received");
    }

    private class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            received.add(data.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return null;
        }
    }
}
