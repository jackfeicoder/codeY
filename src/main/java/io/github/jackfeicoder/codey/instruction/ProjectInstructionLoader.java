package io.github.jackfeicoder.codey.instruction;

import io.github.jackfeicoder.codey.context.TokenEstimator;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.IOException;

/** 只加载工作区根目录 AGENTS.md；有界读取并每次刷新，不跨工作区缓存。 */
public final class ProjectInstructionLoader {
    /** 系统属性可覆盖初始上限，例：-Dcodey.instructions.maxTokens=3000。 */
    private final int maxBytes;
    /** 仅限制规则正文的启发式估算；来源包装另由上下文计入预算。 */
    private final int maxTokens;
    /** 默认32KiB、约2000 token，允许宿主用 JVM 系统属性覆盖。 */
    public ProjectInstructionLoader() {
        this(Integer.getInteger("codey.instructions.maxBytes", 32768),
                Integer.getInteger("codey.instructions.maxTokens", 2000));
    }
    /** 显式上限便于测试或宿主装配，限制自身也有资源上界。 */
    public ProjectInstructionLoader(int maxBytes, int maxTokens) {
        if (maxBytes < 1 || maxBytes > 1048576 || maxTokens < 1 || maxTokens > 1000000)
            throw new IllegalArgumentException("Invalid project instruction limits.");
        this.maxBytes = maxBytes;
        this.maxTokens = maxTokens;
    }
    /** 拒绝符号链接及目录；读前后检查真实位置，有界读取 UTF-8，失败返回可展示状态。 */
    public ProjectInstructions load(WorkspaceBinding workspace) {
        if (workspace.mode() == WorkspaceBinding.Mode.NONE)
            return result(null, "", 0, ProjectInstructions.Status.NONE, "未选择工作区");
        Path path = Path.of(workspace.path()).resolve("AGENTS.md");
        try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                return result(path, "", 0, ProjectInstructions.Status.MISSING, "可执行 /init 生成");
            Path base = Path.of(workspace.path()).toRealPath();
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().getParent().equals(base))
                return invalid(path, "必须是工作区根目录内的普通文件");
            byte[] bytes;
            try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) return invalid(path, "超过 " + maxBytes + " 字节，请精简后重试");
            if (!path.toRealPath().getParent().equals(base)) return invalid(path, "文件位置已变化");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            long tokens = new TokenEstimator().textTokens(text);
            if (tokens > maxTokens) return invalid(path, "估算 " + tokens + " tok，超过 " + maxTokens + " tok，请精简后重试");
            if (text.isBlank()) return invalid(path, "文件为空，请编辑或执行 /init");
            return result(path, text, tokens, ProjectInstructions.Status.LOADED, "已加载；链接按需读取");
        } catch (IOException | SecurityException failed) {
            return invalid(path, "读取失败（" + failed.getClass().getSimpleName() + "）");
        }
    }
    private static ProjectInstructions invalid(Path path, String detail) {
        return result(path, "", 0, ProjectInstructions.Status.INVALID, detail);
    }
    private static ProjectInstructions result(Path path, String content, long tokens,
                                             ProjectInstructions.Status status, String detail) {
        return new ProjectInstructions(path, content, tokens, status, detail);
    }
}
