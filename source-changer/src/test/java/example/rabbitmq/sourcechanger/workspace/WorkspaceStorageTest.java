package example.rabbitmq.sourcechanger.workspace;

import example.rabbitmq.sourcechanger.dto.TreeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStorageTest {

    @TempDir
    Path tempDir;

    private WorkspaceStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new WorkspaceStorage(tempDir.resolve("root").toString(), 1_048_576);
    }

    private MultipartFile zipOf(Path sourceDir) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path file : Files.walk(sourceDir).filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "sources.zip", "application/zip", bytes.toByteArray());
    }

    @Test
    void createWorkspaceExtractsNestedStructure() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("a/b"));
        Files.writeString(src.resolve("a/b/c.txt"), "c");
        Files.writeString(src.resolve("root.txt"), "root");

        String id = storage.createWorkspace(zipOf(src));

        Path ws = tempDir.resolve("root").resolve(id);
        assertThat(Files.readString(ws.resolve("a/b/c.txt"))).isEqualTo("c");
        assertThat(Files.readString(ws.resolve("root.txt"))).isEqualTo("root");
    }

    @Test
    void treeListsFoldersBeforeFilesAlphabetically() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("zdir"));
        Files.writeString(src.resolve("adir.txt"), "a");
        Files.writeString(src.resolve("bdir.txt"), "b");
        Files.writeString(src.resolve("zdir/inner.txt"), "i");

        String id = storage.createWorkspace(zipOf(src));
        TreeNode root = storage.tree(id);

        assertThat(root.type()).isEqualTo("folder");
        List<TreeNode> children = root.children();
        assertThat(children).extracting(TreeNode::name).containsExactly("zdir", "adir.txt", "bdir.txt");
        assertThat(children.get(0).children()).extracting(TreeNode::name).containsExactly("inner.txt");
        assertThat(children.get(1).size()).isPositive();
    }

    @Test
    void readFileReturnsContent() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "hello world");
        String id = storage.createWorkspace(zipOf(src));

        assertThat(storage.readFile(id, "hello.txt")).isEqualTo("hello world");
    }

    @Test
    void writeFilePersistsAndReadsBack() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "before");
        String id = storage.createWorkspace(zipOf(src));

        storage.writeFile(id, "hello.txt", "after");
        assertThat(storage.readFile(id, "hello.txt")).isEqualTo("after");
    }

    @Test
    void writeFileCreatesMissingParentDirectories() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "small");
        String id = storage.createWorkspace(zipOf(src));

        storage.writeFile(id, "deep/a/b/c.txt", "deep");

        assertThat(storage.readFile(id, "deep/a/b/c.txt")).isEqualTo("deep");
    }

    @Test
    void zipEntryEscapingTheWorkspaceIsRejected() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("evil".getBytes());
            zip.closeEntry();
        }
        MultipartFile zip = new MockMultipartFile("file", "evil.zip", "application/zip", bytes.toByteArray());

        assertThatThrownBy(() -> storage.createWorkspace(zip)).isInstanceOf(IOException.class);
    }

    @Test
    void extractedSizeCapRejectsZipBombAndCleansUp() throws IOException {
        WorkspaceStorage tiny = new WorkspaceStorage(tempDir.resolve("tiny").toString(), 128);
        Path src = tempDir.resolve("bomb-src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("big.bin"), "x".repeat(4096));

        assertThatThrownBy(() -> tiny.createWorkspace(zipOf(src)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("limit");

        try (Stream<Path> listing = Files.list(tempDir.resolve("tiny"))) {
            assertThat(listing).isEmpty();
        }
    }

    @Test
    void directoryEntriesAreSkipped() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("dir/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dir/x.txt"));
            zip.write("x".getBytes());
            zip.closeEntry();
        }
        String id = storage.createWorkspace(
                new MockMultipartFile("file", "s.zip", "application/zip", bytes.toByteArray()));

        TreeNode treeRoot = storage.tree(id);
        assertThat(treeRoot.children()).extracting(TreeNode::name).containsExactly("dir");
        assertThat(treeRoot.children().get(0).children()).extracting(TreeNode::name).containsExactly("x.txt");
    }

    @Test
    void readFileOnDirectoryIsRejected() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("dir"));
        Files.writeString(src.resolve("dir/x.txt"), "x");
        String id = storage.createWorkspace(zipOf(src));

        assertThatThrownBy(() -> storage.readFile(id, "dir"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a file");
    }

    @Test
    void readFileTooLargeIsRejected() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "small");
        String id = storage.createWorkspace(zipOf(src));

        storage.writeFile(id, "big.txt", "x".repeat(6 * 1024 * 1024));

        assertThatThrownBy(() -> storage.readFile(id, "big.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void unknownWorkspaceIsRejected() throws IOException {
        assertThatThrownBy(() -> storage.tree("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filePathEscapingTheWorkspaceIsRejected() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "x");
        String id = storage.createWorkspace(zipOf(src));

        assertThatThrownBy(() -> storage.readFile(id, "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.writeFile(id, "../outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(tempDir.resolve("outside.txt"))).isFalse();
    }
}
