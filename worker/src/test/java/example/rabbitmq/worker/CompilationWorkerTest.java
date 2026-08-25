package example.rabbitmq.worker;

import example.rabbitmq.common.cancel.CancellableRequest;
import example.rabbitmq.common.cancel.CancellableRequestScope;
import example.rabbitmq.common.cancel.CancellableRequestType;
import example.rabbitmq.common.cancel.ResourceId;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CompilationWorkerTest {

    @TempDir
    Path tempDir;

    private RabbitTemplate rabbitTemplate;
    private Path workspaceDir;
    private CompilationWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        rabbitTemplate = mock(RabbitTemplate.class);
        workspaceDir = tempDir.resolve("workspaces");
        Files.createDirectories(workspaceDir);
        worker = new CompilationWorker(rabbitTemplate, workspaceDir.toString(), "mvn", 60);
    }

    private void workspace(String id, String... files) throws IOException {
        Path dir = workspaceDir.resolve(id);
        Files.createDirectories(dir);
        for (String file : files) {
            Path path = dir.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "");
        }
    }

    private Path fakeMvn(String script) throws IOException {
        Path mvn = tempDir.resolve("mvn-" + UUID.randomUUID());
        Files.writeString(mvn, "#!/usr/bin/env bash\n" + script);
        mvn.toFile().setExecutable(true);
        return mvn;
    }

    private static CancellableRequest compilationRequest(String workspaceId, UUID id) {
        return new CancellableRequest(CancellableRequestType.COMPILATION,
                CancellableRequestScope.of(new ResourceId("workspace-" + workspaceId)), id, true);
    }

    private static Message requestMessage(String replyTo, String correlationId) {
        MessageProperties props = new MessageProperties();
        if (replyTo != null) {
            props.setReplyTo(replyTo);
        }
        if (correlationId != null) {
            props.setCorrelationId(correlationId);
        }
        return new Message(new byte[0], props);
    }

    private CompilationResultDto replyResult(CompilationWorker w, CancellableRequest req) {
        w.onCompilationRequest(req, requestMessage("reply.q", "corr-1"));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(""), eq("reply.q"), payload.capture(), any(MessagePostProcessor.class));
        return (CompilationResultDto) payload.getValue();
    }

    @Test
    void mavenProjectCompilesViaMavenScript() throws Exception {
        Path mvn = fakeMvn("echo fake-mvn-ran\nexit 0");
        CompilationWorker mvnWorker = new CompilationWorker(rabbitTemplate, workspaceDir.toString(), mvn.toString(), 60);
        workspace("w1", "pom.xml", "src/main/java/A.java");
        Files.writeString(workspaceDir.resolve("w1/src/main/java/A.java"), "class A {}");

        CompilationResultDto result = replyResult(mvnWorker, compilationRequest("w1", UUID.randomUUID()));

        assertThat(result.success()).isTrue();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.SUCCESS);
        assertThat(result.message()).isEqualTo("maven compile ok");
    }

    @Test
    void mavenFailureReturnsTailOfOutput() throws Exception {
        Path mvn = fakeMvn("echo 'first line'\necho 'boom: syntax error'\nexit 1");
        CompilationWorker mvnWorker = new CompilationWorker(rabbitTemplate, workspaceDir.toString(), mvn.toString(), 60);
        workspace("w2", "pom.xml");

        CompilationResultDto result = replyResult(mvnWorker, compilationRequest("w2", UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.FAILURE);
        assertThat(result.message()).contains("boom: syntax error");
    }

    @Test
    void mavenTimeoutFails() throws Exception {
        Path mvn = fakeMvn("sleep 30\nexit 0");
        CompilationWorker slowWorker = new CompilationWorker(rabbitTemplate, workspaceDir.toString(), mvn.toString(), 1);
        workspace("w3", "pom.xml");

        CompilationResultDto result = replyResult(slowWorker, compilationRequest("w3", UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("maven build timed out");
    }

    @Test
    void javacCompilesValidSources() throws Exception {
        workspace("w4", "demo/A.java", "demo/B.java");
        Files.writeString(workspaceDir.resolve("w4/demo/A.java"), "package demo; public class A {}");
        Files.writeString(workspaceDir.resolve("w4/demo/B.java"), "package demo; public class B {}");

        CompilationResultDto result = replyResult(worker, compilationRequest("w4", UUID.randomUUID()));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("compiled 2 java files");
    }

    @Test
    void javacSyntaxErrorFails() throws Exception {
        workspace("w5", "demo/A.java");
        Files.writeString(workspaceDir.resolve("w5/demo/A.java"), "package demo; public class A {");

        CompilationResultDto result = replyResult(worker, compilationRequest("w5", UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("compilation failed");
    }

    @Test
    void noJavaSourcesSucceeds() throws Exception {
        workspace("w6", "readme.txt");

        CompilationResultDto result = replyResult(worker, compilationRequest("w6", UUID.randomUUID()));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("no java sources to compile");
    }

    @Test
    void unknownWorkspaceFails() {
        CompilationResultDto result = replyResult(worker, compilationRequest("does-not-exist", UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("workspace not found");
    }

    @Test
    void pathTraversalOutsideWorkspaceDirIsRejected() {
        CancellableRequest traversal = new CancellableRequest(CancellableRequestType.COMPILATION,
                CancellableRequestScope.of(new ResourceId("workspace-../outside")), UUID.randomUUID(), true);

        CompilationResultDto result = replyResult(worker, traversal);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("workspace not found");
    }

    @Test
    void blankWorkspaceIdFails() {
        CancellableRequest blank = new CancellableRequest(CancellableRequestType.COMPILATION,
                CancellableRequestScope.of(new ResourceId("workspace-")), UUID.randomUUID(), true);

        CompilationResultDto result = replyResult(worker, blank);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no workspace id in scope");
    }

    @Test
    void missingScopeFails() {
        CancellableRequest noScope = new CancellableRequest(CancellableRequestType.COMPILATION, null,
                UUID.randomUUID(), true);

        CompilationResultDto result = replyResult(worker, noScope);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no workspace id in scope");
    }

    @Test
    void unexpectedRequestTypeFails() {
        CancellableRequest save = new CancellableRequest(CancellableRequestType.SAVE,
                CancellableRequestScope.of(new ResourceId("workspace-w1")), UUID.randomUUID(), true);

        CompilationResultDto result = replyResult(worker, save);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("unexpected request type");
    }

    @Test
    void replyCarriesCorrelationIdAndReplyTo() throws Exception {
        workspace("w8", "readme.txt");
        worker.onCompilationRequest(compilationRequest("w8", UUID.randomUUID()),
                requestMessage("my-reply", "corr-42"));

        ArgumentCaptor<MessagePostProcessor> mpp = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(""), eq("my-reply"), (Object) any(CompilationResultDto.class), mpp.capture());
        Message reply = mpp.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(reply.getMessageProperties().getCorrelationId()).isEqualTo("corr-42");
    }

    @Test
    void missingReplyToDropsResultWithoutSending() throws Exception {
        workspace("w9", "readme.txt");
        worker.onCompilationRequest(compilationRequest("w9", UUID.randomUUID()),
                requestMessage(null, "corr-1"));

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }
}
