package io.github.jackfeicoder.codey.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 工作区与普通聊天明确区分，路径解析不改变进程目录。 */
class WorkspaceResolverTest {
    @TempDir Path directory;

    @Test
    void resolvesRelativeDirectoryWithSpacesWithoutChangingStartup() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("项目 with spaces"));
        String before = System.getProperty("user.dir");
        WorkspaceBinding binding = WorkspaceResolver.resolve(directory, "项目 with spaces");
        assertEquals(workspace.toRealPath().toString(), binding.path());
        assertTrue(WorkspaceResolver.available(binding));
        assertEquals(before, System.getProperty("user.dir"));
        Files.delete(workspace);
        assertFalse(WorkspaceResolver.available(binding));
        assertTrue(WorkspaceResolver.available(WorkspaceBinding.none()));
    }

    @Test
    void rejectsMissingPathsAndFiles() throws Exception {
        Files.writeString(directory.resolve("file"), "fixture");
        assertThrows(IllegalArgumentException.class, () -> WorkspaceResolver.resolve(directory, "missing"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceResolver.resolve(directory, "file"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceResolver.resolve(directory, ""));
    }
}
