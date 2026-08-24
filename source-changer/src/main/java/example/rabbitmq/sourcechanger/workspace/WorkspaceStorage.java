package example.rabbitmq.sourcechanger.workspace;

import example.rabbitmq.sourcechanger.dto.TreeNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Stores uploaded source zips on disk, one directory per workspace, and serves
 * the file tree and file contents used by the web UI.
 *
 * <p>Three limits protect the server:
 * <ul>
 * <li>upload size (multipart, see {@code spring.servlet.multipart.max-file-size});</li>
 * <li>total extracted size ({@code example.workspace.max-extracted-bytes}) —
 *     a zip bomb (small compressed, huge uncompressed) is rejected during
 *     extraction and the partial workspace is removed;</li>
 * <li>per-file size read into the editor
 *     ({@link #MAX_EDITABLE_FILE_BYTES}).</li>
 * </ul>
 */
@Component
public class WorkspaceStorage {

    private static final int MAX_EDITABLE_FILE_BYTES = 5 * 1024 * 1024;

    private final Path rootDir;
    private final long maxExtractedBytes;

    public WorkspaceStorage(
            @Value("${example.workspace.dir:./workspaces}") String workspaceDir,
            @Value("${example.workspace.max-extracted-bytes:104857600}") long maxExtractedBytes) throws IOException {
        this.rootDir = Paths.get(workspaceDir).toAbsolutePath().normalize();
        this.maxExtractedBytes = maxExtractedBytes;
        Files.createDirectories(rootDir);
    }

    /** Extracts the uploaded zip into a fresh workspace and returns its id. */
    public String createWorkspace(MultipartFile zip) throws IOException {
        String id = UUID.randomUUID().toString();
        Path workspace = rootDir.resolve(id);
        Files.createDirectories(workspace);
        long extracted = 0;
        try (ZipInputStream zipIn = new ZipInputStream(zip.getInputStream())) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                Path out = workspace.resolve(entry.getName()).normalize();
                if (!out.startsWith(workspace)) {
                    throw new IOException("Zip entry escapes the workspace: " + entry.getName());
                }
                Files.createDirectories(out.getParent());
                try (OutputStream outStream = Files.newOutputStream(out)) {
                    extracted += zipIn.transferTo(outStream);
                    if (extracted > maxExtractedBytes) {
                        throw new IOException("Extracted size exceeds the limit of " + maxExtractedBytes + " bytes");
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursively(workspace);
            throw e;
        }
        return id;
    }

    public TreeNode tree(String workspaceId) throws IOException {
        Path dir = workspaceDir(workspaceId);
        return buildNode(dir, "", dir.getFileName().toString());
    }

    public String readFile(String workspaceId, String path) throws IOException {
        Path file = resolveFile(workspaceId, path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + path);
        }
        if (Files.size(file) > MAX_EDITABLE_FILE_BYTES) {
            throw new IllegalArgumentException("File too large to edit: " + path);
        }
        return Files.readString(file);
    }

    public void writeFile(String workspaceId, String path, String content) throws IOException {
        Path file = resolveFile(workspaceId, path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }

    /** Resolves the workspace directory, rejecting unknown ids. */
    public Path workspaceDir(String workspaceId) {
        Path dir = rootDir.resolve(workspaceId).normalize();
        if (!dir.startsWith(rootDir) || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
        }
        return dir;
    }

    private Path resolveFile(String workspaceId, String path) {
        Path dir = workspaceDir(workspaceId);
        Path file = dir.resolve(path).normalize();
        if (!file.startsWith(dir)) {
            throw new IllegalArgumentException("Path escapes the workspace: " + path);
        }
        return file;
    }

    private TreeNode buildNode(Path file, String path, String name) throws IOException {
        if (!Files.isDirectory(file)) {
            return TreeNode.file(name, path, Files.size(file));
        }
        List<TreeNode> children = new ArrayList<>();
        try (Stream<Path> listing = Files.list(file)) {
            for (Path child : listing
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList()) {
                children.add(buildNode(child, childPath(path, child), child.getFileName().toString()));
            }
        }
        return TreeNode.folder(name, path, children);
    }

    private String childPath(String parentPath, Path child) {
        String name = child.getFileName().toString();
        return parentPath.isEmpty() ? name : parentPath + "/" + name;
    }
}
