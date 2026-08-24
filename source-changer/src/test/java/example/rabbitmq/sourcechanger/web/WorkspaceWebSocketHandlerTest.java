package example.rabbitmq.sourcechanger.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import example.rabbitmq.common.cancel.SharedCancellationService;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.mq.CompletionService;
import example.rabbitmq.sourcechanger.mq.CompilationService;
import example.rabbitmq.sourcechanger.worker.LocalWorker;
import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test of the WebSocket protocol contract: every message the frontend
 * depends on (tree, fileContent, saved, compileResult, completeResult, error)
 * is asserted here so FE/BE contract breaks are caught without a browser.
 */
class WorkspaceWebSocketHandlerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkspaceStorage storage;
    private CompilationService compilation;
    private CompletionService completion;
    private SharedCancellationService cancellation;
    private LocalWorker localWorker;
    private WorkspaceWebSocketHandler handler;
    private WebSocketSession session;
    private List<String> sent;

    private String workspaceId;

    @BeforeEach
    void setUp() throws IOException {
        storage = new WorkspaceStorage(tempDir.resolve("root").toString(), 1_048_576);
        compilation = mock(CompilationService.class);
        completion = mock(CompletionService.class);
        cancellation = mock(SharedCancellationService.class);
        when(cancellation.startRequestAndCancelPrevious(any())).thenReturn(true);

        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        workspaceId = storage.createWorkspace(multipartZip(src));

        localWorker = mock(LocalWorker.class);
        handler = new WorkspaceWebSocketHandler(mapper, storage, compilation, completion, cancellation, localWorker);

        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
    }

    private static org.springframework.web.multipart.MultipartFile multipartZip(Path dir) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path file : Files.walk(dir).filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(dir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return new org.springframework.mock.web.MockMultipartFile("file", "sources.zip", "application/zip", bytes.toByteArray());
    }

    private void send(String json) throws Exception {
        handler.handleTextMessage(session, new TextMessage(json));
    }

    private JsonNode awaitMessage(String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (sent) {
                for (String payload : sent) {
                    JsonNode node = mapper.readTree(payload);
                    if (type.equals(node.path("type").asText())) {
                        return node;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No message of type '" + type + "' in " + sent);
    }

    @Test
    void subscribeReturnsTree() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");

        JsonNode tree = awaitMessage("tree");
        assertThat(tree.path("workspaceId").asText()).isEqualTo(workspaceId);
        assertThat(tree.path("tree").path("type").asText()).isEqualTo("folder");
        assertThat(tree.path("tree").path("children").get(0).path("name").asText()).isEqualTo("A.java");
    }

    @Test
    void subscribeUnknownWorkspaceReturnsError() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"does-not-exist\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Unknown workspace");
    }

    @Test
    void fileRequestReturnsContent() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"file\",\"path\":\"A.java\"}");

        JsonNode content = awaitMessage("fileContent");
        assertThat(content.path("path").asText()).isEqualTo("A.java");
        assertThat(content.path("content").asText()).isEqualTo("class A {}");
    }

    @Test
    void savePersistsAndReplies() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"save\",\"path\":\"A.java\",\"content\":\"class A { int x; }\"}");

        JsonNode saved = awaitMessage("saved");
        assertThat(saved.path("path").asText()).isEqualTo("A.java");
        assertThat(saved.path("timestamp").asText()).isNotBlank();
        assertThat(Files.readString(tempDir.resolve("root").resolve(workspaceId).resolve("A.java")))
                .isEqualTo("class A { int x; }");
    }

    @Test
    void compileSuccessEchoesRequestId() throws Exception {
        when(compilation.compile()).thenReturn(
                new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "compiled"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"compile\",\"requestId\":\"r1\"}");

        JsonNode result = awaitMessage("compileResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("resultType").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("requestId").asText()).isEqualTo("r1");
    }

    @Test
    void compileFailureIsPropagatedAsMessage() throws Exception {
        when(compilation.compile()).thenThrow(new IllegalStateException("compiler crashed"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"compile\",\"requestId\":\"r2\"}");

        JsonNode result = awaitMessage("compileResult");
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("message").asText()).contains("compiler crashed");
        assertThat(result.path("requestId").asText()).isEqualTo("r2");
    }

    @Test
    void completionReturnsSuggestions() throws Exception {
        when(completion.complete(any())).thenReturn(
                new CompletionResultDto(true, "ok", List.of("foo", "bar")));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"complete\",\"path\":\"A.java\",\"line\":1,\"column\":10,\"text\":\"class A {\"}");

        JsonNode result = awaitMessage("completeResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("suggestions")).hasSize(2);
        assertThat(result.path("suggestions").get(0).asText()).isEqualTo("foo");
    }

    @Test
    void compileUsesLocalWorkerWhenEnabled() throws Exception {
        when(localWorker.isEnabled()).thenReturn(true);
        when(localWorker.compile(workspaceId)).thenReturn(
                new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "local ok"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"compile\",\"requestId\":\"r7\"}");

        JsonNode result = awaitMessage("compileResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("requestId").asText()).isEqualTo("r7");
        assertThat(result.path("message").asText()).isEqualTo("local ok");
        verify(compilation, never()).compile();
    }

    @Test
    void compileLocalWorkerFailureIsPropagated() throws Exception {
        when(localWorker.isEnabled()).thenReturn(true);
        when(localWorker.compile(workspaceId)).thenReturn(
                new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, "broken sources"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"compile\"}");

        JsonNode result = awaitMessage("compileResult");
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("message").asText()).isEqualTo("broken sources");
    }

    @Test
    void completeUsesLocalWorkerWhenEnabled() throws Exception {
        when(localWorker.isEnabled()).thenReturn(true);
        when(localWorker.complete(eq(workspaceId), any())).thenReturn(
                new CompletionResultDto(true, "ok", List.of("alpha", "beta")));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"complete\",\"path\":\"A.java\",\"line\":1,\"column\":1,\"text\":\"\"}");

        JsonNode result = awaitMessage("completeResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("suggestions")).hasSize(2);
        verify(completion, never()).complete(any());
    }

    @Test
    void subscribeBlankWorkspaceIdReturnsError() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("workspaceId is required");
    }

    @Test
    void fileRequestOnDirectoryReturnsError() throws Exception {
        storage.writeFile(workspaceId, "dir/x.txt", "x");
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"file\",\"path\":\"dir\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Not a file");
    }

    @Test
    void compileWithoutRequestIdOmitsRequestIdField() throws Exception {
        when(compilation.compile()).thenReturn(
                new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "ok"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"compile\"}");

        JsonNode result = awaitMessage("compileResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.has("requestId")).isFalse();
    }

    @Test
    void completeWithoutRequestIdOmitsRequestIdField() throws Exception {
        when(completion.complete(any())).thenReturn(new CompletionResultDto(true, "ok", List.of("a")));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"complete\",\"path\":\"A.java\",\"line\":1,\"column\":1,\"text\":\"\"}");

        JsonNode result = awaitMessage("completeResult");
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.has("requestId")).isFalse();
    }

    @Test
    void closedSessionReceivesNoReplies() throws Exception {
        WebSocketSession closed = mock(WebSocketSession.class);
        when(closed.getId()).thenReturn("closed-1");
        when(closed.isOpen()).thenReturn(false);
        List<String> closedSent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            closedSent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(closed).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(closed,
                new TextMessage("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}"));

        assertThat(closedSent).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void afterConnectionClosedRequiresSubscribeAgain() throws Exception {
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        send("{\"type\":\"file\",\"path\":\"A.java\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Subscribe");
    }

    @Test
    void completionFailureEchoesRequestId() throws Exception {
        when(completion.complete(any())).thenThrow(new IllegalStateException("no worker"));
        send("{\"type\":\"subscribe\",\"workspaceId\":\"" + workspaceId + "\"}");
        send("{\"type\":\"complete\",\"requestId\":\"q9\",\"path\":\"A.java\",\"line\":1,\"column\":1,\"text\":\"\"}");

        JsonNode result = awaitMessage("completeResult");
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("message").asText()).contains("no worker");
        assertThat(result.path("requestId").asText()).isEqualTo("q9");
    }

    @Test
    void unknownMessageTypeReturnsError() throws Exception {
        send("{\"type\":\"nonsense\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Unknown message type");
    }

    @Test
    void malformedPayloadReturnsError() throws Exception {
        send("not-json{");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Malformed");
    }

    @Test
    void operationsRequireSubscriptionFirst() throws Exception {
        send("{\"type\":\"file\",\"path\":\"A.java\"}");

        JsonNode error = awaitMessage("error");
        assertThat(error.path("message").asText()).contains("Subscribe");
    }
}
