package example.rabbitmq.worker;

import example.rabbitmq.common.cancel.CancellableRequest;
import example.rabbitmq.common.cancel.CancellableRequestType;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Consumes compilation requests from the requestCompilation queue, compiles
 * the shared workspace (Maven for pom projects, the JDK compiler otherwise)
 * and replies with a CompilationResultDto on the request's replyTo queue.
 */
@Component
public class CompilationWorker {

  private static final Logger log = LoggerFactory.getLogger(CompilationWorker.class);
  private static final Pattern WORKSPACE_PREFIX = Pattern.compile("^workspace-");
  private static final int MESSAGE_TAIL_CHARS = 2000;

  private final RabbitTemplate rabbitTemplate;
  private final Path workspaceDir;
  private final String mavenPath;
  private final long buildTimeoutSeconds;

  public CompilationWorker(
      RabbitTemplate rabbitTemplate,
      @Value("${example.workspace.dir:./workspaces}") String workspaceDir,
      @Value("${example.worker.maven-path:mvn}") String mavenPath,
      @Value("${example.worker.build-timeout:300}") long buildTimeoutSeconds) {
    this.rabbitTemplate = rabbitTemplate;
    this.workspaceDir = Path.of(workspaceDir).toAbsolutePath().normalize();
    this.mavenPath = mavenPath;
    this.buildTimeoutSeconds = buildTimeoutSeconds;
  }

  @RabbitListener(queues = "${example.mq.queue.compilation.request:requestCompilation}")
  public void onCompilationRequest(CancellableRequest request, Message message) {
    log.info("compilation request received: type={} scope={} requestId={}",
        request.type(), request.scope(), request.requestId());
    CompilationResultDto result = compile(request);
    log.info("compilation finished: success={} message={}", result.success(), result.message());
    reply(message, result);
  }

  private CompilationResultDto compile(CancellableRequest request) {
    try {
      if (request.type() != CancellableRequestType.COMPILATION) {
        return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE,
            "unexpected request type: " + request.type());
      }
      String resourceId = request.scope() == null || request.scope().resourceId() == null
          ? "" : request.scope().resourceId().id();
      String workspaceId = WORKSPACE_PREFIX.matcher(resourceId).replaceFirst("");
      if (workspaceId.isBlank()) {
        return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, "no workspace id in scope");
      }
      Path dir = workspaceDir.resolve(workspaceId).normalize();
      if (!dir.startsWith(workspaceDir) || !Files.isDirectory(dir)) {
        return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE,
            "workspace not found: " + workspaceId);
      }
      if (Files.isRegularFile(dir.resolve("pom.xml"))) {
        return compileWithMaven(dir);
      }
      return compileWithJavac(dir);
    } catch (Exception e) {
      return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, e.getMessage());
    }
  }

  private CompilationResultDto compileWithMaven(Path dir) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(mavenPath, "-q", "-DskipTests", "compile");
    builder.directory(dir.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> {
      try (InputStream in = process.getInputStream()) {
        in.transferTo(output);
      } catch (IOException ignored) {
        // stream closed when the process is destroyed
      }
    }, "maven-output-reader");
    reader.setDaemon(true);
    reader.start();
    if (!process.waitFor(buildTimeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      reader.join(2000);
      return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, "maven build timed out");
    }
    reader.join(5000);
    String text = output.toString(StandardCharsets.UTF_8);
    if (process.exitValue() == 0) {
      return new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "maven compile ok");
    }
    return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, tail(text));
  }

  private CompilationResultDto compileWithJavac(Path dir) throws IOException {
    List<Path> sources;
    try (Stream<Path> walk = Files.walk(dir)) {
      sources = walk
          .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
          .sorted()
          .toList();
    }
    if (sources.isEmpty()) {
      return new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS, "no java sources to compile");
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, "no JDK compiler available");
    }
    Path out = Files.createTempDirectory("javac-out");
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
      Boolean ok = compiler.getTask(null, fileManager, diagnostics,
          List.of("-d", out.toString(), "-proc:none"), null, units).call();
      if (Boolean.TRUE.equals(ok)) {
        return new CompilationResultDto(true, CompilationResultDto.ResultType.SUCCESS,
            "compiled " + sources.size() + " java files");
      }
      StringBuilder message = new StringBuilder("compilation failed:");
      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
          message.append('\n').append(diagnostic);
        }
      }
      return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, message.toString());
    }
  }

  private String tail(String output) {
    String trimmed = output.trim();
    return trimmed.length() > MESSAGE_TAIL_CHARS
        ? "..." + trimmed.substring(trimmed.length() - MESSAGE_TAIL_CHARS)
        : trimmed;
  }

  private void reply(Message request, Object payload) {
    String replyTo = request.getMessageProperties().getReplyTo();
    if (replyTo == null) {
      log.warn("no replyTo on request, result dropped");
      return;
    }
    String correlationId = request.getMessageProperties().getCorrelationId();
    rabbitTemplate.convertAndSend("", replyTo, payload, m -> {
      if (correlationId != null) {
        m.getMessageProperties().setCorrelationId(correlationId);
      }
      return m;
    });
  }
}
