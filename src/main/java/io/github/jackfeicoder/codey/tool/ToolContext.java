package io.github.jackfeicoder.codey.tool;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import io.github.jackfeicoder.codey.workspace.WorkspaceResolver;

/**
 * 一次执行的宿主上下文，不来自模型 arguments，不包含密钥。
 * @param workspace 当前会话工作区或 NONE，模型不能通过参数替换它
 * @param maxOutputChars 输出最多保留的 Unicode 码点数量，截断标记另存在结果字段
 */
public record ToolContext(WorkspaceBinding workspace, int maxOutputChars) {
    /** 首版输出上限，避免工具结果无限增长；具体读取工具仍需限制读取量。 */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 16_000;

    /** 使用默认输出上限构造上下文。 */
    public ToolContext(WorkspaceBinding workspace) {
        this(workspace, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** 上下文由宿主创建，必须明确工作区状态和正数输出上限。 */
    public ToolContext {
        if (workspace == null || maxOutputChars <= 0) {
            throw new IllegalArgumentException("Tool context requires a workspace binding and positive output limit.");
        }
    }

    /** 用于筛选可向模型展示的定义，失效目录不作为可用项目工作区。 */
    public boolean hasWorkspace() {
        return workspace.mode() == WorkspaceBinding.Mode.DIRECTORY && WorkspaceResolver.available(workspace);
    }
}
