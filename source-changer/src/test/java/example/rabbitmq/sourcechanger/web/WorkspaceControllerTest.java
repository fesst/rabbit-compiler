package example.rabbitmq.sourcechanger.web;

import example.rabbitmq.sourcechanger.workspace.WorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceControllerTest {

    private WorkspaceStorage storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        storage = mock(WorkspaceStorage.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceController(storage)).build();
    }

    @Test
    void uploadZipReturnsWorkspaceId() throws Exception {
        when(storage.createWorkspace(any())).thenReturn("ws-42");

        mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("file", "sources.zip", "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value("ws-42"));

        verify(storage).createWorkspace(any());
    }

    @Test
    void uploadRejectsNonZip() throws Exception {
        mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("file", "sources.txt", "text/plain", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest());

        verify(storage, never()).createWorkspace(any());
    }

    @Test
    void uploadRejectsFileWithNullOriginalFilename() throws Exception {
        mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("file", null, "application/zip", new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest());

        verify(storage, never()).createWorkspace(any());
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        mvc.perform(multipart("/api/workspaces")
                        .file(new MockMultipartFile("file", "sources.zip", "application/zip", new byte[0])))
                .andExpect(status().isBadRequest());

        verify(storage, never()).createWorkspace(any());
    }
}
