package example.rabbitmq.sourcechanger.worker;

import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * In-process worker behind {@code example.worker.local=true}: compiles the
 * workspace and answers completion without a RabbitMQ broker. Used by the
 * e2e tests and by the docker stack out of the box; with the flag off the
 * request/reply path over RabbitMQ is used instead.
 *
 * <p>Compilation is real: Maven ({@code mvn -DskipTests compile}) when the
 * workspace has a {@code pom.xml}, the JDK compiler otherwise.
 */
@Component
public class LocalWorker {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{1,}");
    private static final int MAX_SUGGESTIONS = 20;
    private static final int MESSAGE_TAIL_CHARS = 2000;

    private final WorkspaceStorage storage;
    private final boolean enabled;
    private final String mavenPath;
    private final long buildTimeoutSeconds;

    public LocalWorker(
            WorkspaceStorage storage,
            @Value("${example.worker.local:false}") boolean enabled,
            @Value("${example.worker.maven-path:mvn}") String mavenPath,
            @Value("${example.worker.build-timeout:300}") long buildTimeoutSeconds) {
        this.storage = storage;
        this.enabled = enabled;
        this.mavenPath = mavenPath;
        this.buildTimeoutSeconds = buildTimeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CompilationResultDto compile(String workspaceId) {
        try {
            Path dir = storage.workspaceDir(workspaceId);
            if (Files.isRegularFile(dir.resolve("pom.xml"))) {
                return compileWithMaven(dir);
            }
            return compileWithJavac(dir);
        } catch (Exception e) {
            return new CompilationResultDto(false, CompilationResultDto.ResultType.FAILURE, e.getMessage());
        }
    }

    /** Suggests identifiers found in the request text (stub completion). */
    public CompletionResultDto complete(String workspaceId, CompletionRequestDto request) {
        Set<String> words = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(request.text() == null ? "" : request.text());
        while (matcher.find() && words.size() < MAX_SUGGESTIONS) {
            words.add(matcher.group());
        }
        return new CompletionResultDto(true, "local completion", List.copyOf(words));
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
            List<String> options = List.of("-d", out.toString(), "-proc:none");
            Boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
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
}
