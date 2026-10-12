package io.github.jackfeicoder.codey.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 将用户路径相对启动目录解析成真实目录，不改变进程目录或模型配置来源。 */
public final class WorkspaceResolver {
    /** 仅使用静态路径解析方法。 */
    private WorkspaceResolver() { }

    /** 校验路径存在、为目录且可读；不要求 Git 仓库或可写权限。 */
    public static WorkspaceBinding resolve(Path startupDirectory, String input) {
        try {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException();
            }
            Path directory = startupDirectory.resolve(input).toRealPath(); // 消除相对段与链接。
            if (!Files.isDirectory(directory) || !Files.isReadable(directory)) {
                throw new IllegalArgumentException();
            }
            return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toString());
        } catch (IOException | IllegalArgumentException failed) {
            throw new IllegalArgumentException("Workspace must be an existing readable directory.");
        }
    }

    /** 恢复时重新确认绑定目录有效，无工作区始终有效。 */
    public static boolean available(WorkspaceBinding binding) {
        if (binding.mode() == WorkspaceBinding.Mode.NONE) {
            return true;
        }
        try {
            Path path = Path.of(binding.path());
            return Files.isDirectory(path) && Files.isReadable(path)
                    && path.toRealPath().equals(path);
        } catch (IOException failed) {
            return false;
        }
    }
}
