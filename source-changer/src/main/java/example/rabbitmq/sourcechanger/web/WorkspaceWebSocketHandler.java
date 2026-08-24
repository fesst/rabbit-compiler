package example.rabbitmq.sourcechanger.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import example.rabbitmq.common.cancel.CancellableRequest;
import example.rabbitmq.common.cancel.CancellableRequestHolder;
import example.rabbitmq.common.cancel.CancellableRequestScope;
import example.rabbitmq.common.cancel.CancellableRequestType;
import example.rabbitmq.common.cancel.ResourceId;
import example.rabbitmq.common.cancel.SharedCancellationService;
import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.dto.TreeNode;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.mq.CompletionService;
import example.rabbitmq.sourcechanger.mq.CompilationService;
import example.rabbitmq.sourcechanger.worker.LocalWorker;
import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket endpoint for the web IDE workspace. After the zip has been
 * uploaded over REST, the client subscribes here and the whole workspace
 * (tree, file contents, saves, compilation and completion) is exchanged as
 * JSON messages:
 *
 * <pre>
 * client → server: {type: subscribe|file|save|compile|complete, ...}
 * server → client: {type: tree|fileContent|saved|compileResult|completeResult|error, ...}
 * </pre>
 *
 * Compilation and completion are dispatched to a small worker pool so a slow
 * request/reply does not block the WebSocket connection.
 */
@Component
public class WorkspaceWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper;
    private final WorkspaceStorage storage;
    private final CompilationService compilationService;
    private final CompletionService completionService;
    private final SharedCancellationService cancellationService;
    private final LocalWorker localWorker;

    private final Map<String, String> sessionWorkspaces = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "workspace-worker");
        thread.setDaemon(true);
        return thread;
    });

    public WorkspaceWebSocketHandler(
            ObjectMapper mapper,
            WorkspaceStorage storage,
            CompilationService compilationService,
            CompletionService completionService,
            SharedCancellationService cancellationService,
            LocalWorker localWorker) {
        this.mapper = mapper;
        this.storage = storage;
        this.compilationService = compilationService;
        this.completionService = completionService;
        this.cancellationService = cancellationService;
        this.localWorker = localWorker;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode msg;
        try {
            msg = mapper.readTree(message.getPayload());
        } catch (IOException e) {
            send(session, error("Malformed message: " + e.getMessage()));
            return;
        }
        String type = msg.path("type").asText();
        try {
            switch (type) {
                case "subscribe" -> subscribe(session, msg);
                case "file" -> loadFile(session, msg);
                case "save" -> saveFile(session, msg);
                case "compile" -> workers.submit(() -> compile(session, msg));
                case "complete" -> workers.submit(() -> complete(session, msg));
                default -> send(session, error("Unknown message type: " + type));
            }
        } catch (Exception e) {
            send(session, error(rootMessage(e)));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionWorkspaces.remove(session.getId());
    }

    private void subscribe(WebSocketSession session, JsonNode msg) throws IOException {
        String workspaceId = msg.path("workspaceId").asText();
        if (workspaceId.isBlank()) {
            send(session, error("workspaceId is required"));
            return;
        }
        TreeNode tree = storage.tree(workspaceId); // throws if unknown
        sessionWorkspaces.put(session.getId(), workspaceId);
        ObjectNode reply = mapper.createObjectNode();
        reply.put("type", "tree");
        reply.put("workspaceId", workspaceId);
        reply.set("tree", mapper.valueToTree(tree));
        send(session, reply);
    }

    private void loadFile(WebSocketSession session, JsonNode msg) throws IOException {
        String path = msg.path("path").asText();
        String workspaceId = requireWorkspace(session);
        ObjectNode reply = mapper.createObjectNode();
        reply.put("type", "fileContent");
        reply.put("path", path);
        reply.put("content", storage.readFile(workspaceId, path));
        send(session, reply);
    }

    private void saveFile(WebSocketSession session, JsonNode msg) throws IOException {
        String path = msg.path("path").asText();
        String content = msg.path("content").asText();
        String workspaceId = requireWorkspace(session);
        storage.writeFile(workspaceId, path, content);
        ObjectNode reply = mapper.createObjectNode();
        reply.put("type", "saved");
        reply.put("path", path);
        reply.put("timestamp", Instant.now().toString());
        send(session, reply);
    }

    private void compile(WebSocketSession session, JsonNode msg) {
        try {
            String workspaceId = requireWorkspace(session);
            CompilationResultDto result;
            if (localWorker.isEnabled()) {
                result = localWorker.compile(workspaceId);
            } else {
                CancellableRequest request = new CancellableRequest(
                        CancellableRequestType.COMPILATION,
                        CancellableRequestScope.of(new ResourceId("workspace-" + workspaceId)),
                        UUID.randomUUID(),
                        true);
                result = CancellableRequestHolder.doWithNewRequest(
                        cancellationService, request, compilationService::compile);
            }
            ObjectNode reply = mapper.createObjectNode();
            reply.put("type", "compileResult");
            String requestId = msg.path("requestId").asText("");
            if (!requestId.isBlank()) {
                reply.put("requestId", requestId);
            }
            reply.put("success", result.success());
            reply.put("resultType", result.resultType().name());
            reply.put("message", result.message() == null ? "" : result.message());
            send(session, reply);
        } catch (Exception e) {
            ObjectNode reply = mapper.createObjectNode();
            reply.put("type", "compileResult");
            String requestId = msg.path("requestId").asText("");
            if (!requestId.isBlank()) {
                reply.put("requestId", requestId);
            }
            reply.put("success", false);
            reply.put("resultType", "ERROR");
            reply.put("message", rootMessage(e));
            send(session, reply);
        }
    }

    private void complete(WebSocketSession session, JsonNode msg) {
        try {
            String workspaceId = requireWorkspace(session);
            String path = msg.path("path").asText();
            int line = msg.path("line").asInt(1);
            int column = msg.path("column").asInt(1);
            String text = msg.path("text").asText();
            CompletionRequestDto request = new CompletionRequestDto(path, line, column, text);
            CompletionResultDto result = localWorker.isEnabled()
                    ? localWorker.complete(workspaceId, request)
                    : completionService.complete(request);
            ObjectNode reply = mapper.createObjectNode();
            reply.put("type", "completeResult");
            String requestId = msg.path("requestId").asText("");
            if (!requestId.isBlank()) {
                reply.put("requestId", requestId);
            }
            reply.put("success", result.success());
            reply.put("message", result.message() == null ? "" : result.message());
            ArrayNode suggestions = reply.putArray("suggestions");
            result.suggestions().forEach(suggestions::add);
            send(session, reply);
        } catch (Exception e) {
            ObjectNode reply = mapper.createObjectNode();
            reply.put("type", "completeResult");
            String requestId = msg.path("requestId").asText("");
            if (!requestId.isBlank()) {
                reply.put("requestId", requestId);
            }
            reply.put("success", false);
            reply.put("message", rootMessage(e));
            send(session, reply);
        }
    }

    private String requireWorkspace(WebSocketSession session) {
        String workspaceId = sessionWorkspaces.get(session.getId());
        if (workspaceId == null) {
            throw new IllegalStateException("Subscribe to a workspace first");
        }
        return workspaceId;
    }

    private ObjectNode error(String message) {
        ObjectNode reply = mapper.createObjectNode();
        reply.put("type", "error");
        reply.put("message", message);
        return reply;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    private void send(WebSocketSession session, ObjectNode payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
            }
        } catch (IOException ignored) {
            // client went away while we were replying
        }
    }
}
