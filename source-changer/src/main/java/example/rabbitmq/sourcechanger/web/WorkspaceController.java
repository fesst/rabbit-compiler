package example.rabbitmq.sourcechanger.web;

import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * REST entry point: accepts the source zip. Everything that happens after the
 * upload (tree, file contents, saves, compilation, completion) is exchanged
 * over the WebSocket endpoint {@code /ws/workspace}.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceStorage storage;

    public WorkspaceController(WorkspaceStorage storage) {
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (file.isEmpty() || !name.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A .zip file is required");
        }
        String workspaceId = storage.createWorkspace(file);
        return Map.of("workspaceId", workspaceId);
    }
}
