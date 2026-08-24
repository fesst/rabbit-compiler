package example.rabbitmq.sourcechanger.worker;

import example.rabbitmq.sourcechanger.dto.CompletionRequestDto;
import example.rabbitmq.sourcechanger.dto.CompletionResultDto;
import example.rabbitmq.sourcechanger.dto.CompilationResultDto;
import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LocalWorkerTest {

    @TempDir
    Path tempDir;

    private WorkspaceStorage storage;
    private LocalWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        storage = new WorkspaceStorage(tempDir.resolve("root").toString(), 1_048_576);
        worker = new LocalWorker(storage, true, "mvn");
    }

    private String workspaceWith(String... files) throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        for (String file : files) {
            Path path = src.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path p : Files.walk(src).filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(src.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        return storage.createWorkspace(
                new MockMultipartFile("file", "s.zip", "application/zip", bytes.toByteArray()));
    }

    @Test
    void enabledFlagReflectsConfiguration() throws IOException {
        assertThat(worker.isEnabled()).isTrue();
        assertThat(new LocalWorker(storage, false, "mvn").isEnabled()).isFalse();
    }

    @Test
    void compilesValidJavaSourcesWithJavac() throws IOException {
        String id = workspaceWith(
                "demo/A.java",
                "demo/B.java");
        Files.writeString(storage.workspaceDir(id).resolve("demo/A.java"), "package demo; public class A {}");
        Files.writeString(storage.workspaceDir(id).resolve("demo/B.java"), "package demo; public class B {}");

        CompilationResultDto result = worker.compile(id);

        assertThat(result.success()).isTrue();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.SUCCESS);
        assertThat(result.message()).contains("compiled 2 java files");
    }

    @Test
    void compileWithSyntaxErrorFails() throws IOException {
        String id = workspaceWith("demo/A.java");
        Files.writeString(storage.workspaceDir(id).resolve("demo/A.java"), "package demo; public class A {");

        CompilationResultDto result = worker.compile(id);

        assertThat(result.success()).isFalse();
        assertThat(result.resultType()).isEqualTo(CompilationResultDto.ResultType.FAILURE);
        assertThat(result.message()).contains("compilation failed");
    }

    @Test
    void compileWithoutJavaSourcesSucceeds() throws IOException {
        String id = workspaceWith("readme.txt");

        CompilationResultDto result = worker.compile(id);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("no java sources to compile");
    }

    @Test
    void completeReturnsUniqueWordsFromText() throws IOException {
        String id = workspaceWith("A.java");

        CompletionResultDto result = worker.complete(id,
                new CompletionRequestDto("A.java", 1, 1, "foo bar foo baz qux"));

        assertThat(result.success()).isTrue();
        assertThat(result.suggestions()).containsExactlyInAnyOrder("foo", "bar", "baz", "qux");
    }

    @Test
    void completeCapsSuggestions() throws IOException {
        String id = workspaceWith("A.java");
        String text = String.join(" ", java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "word" + i).toList());

        CompletionResultDto result = worker.complete(id, new CompletionRequestDto("A.java", 1, 1, text));

        assertThat(result.suggestions()).hasSize(20);
    }
}
