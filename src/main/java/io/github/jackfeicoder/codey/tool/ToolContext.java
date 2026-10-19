package io.github.jackfeicoder.codey.tool;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import io.github.jackfeicoder.codey.workspace.WorkspaceResolver;
import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.*;

/**
 * 一次执行的宿主上下文，不来自模型 arguments，不包含密钥。
 * @param workspace 当前会话工作区或 NONE，模型不能通过参数替换它
 * @param maxOutputChars 输出最多保留的 Unicode 码点数量，截断标记另存在结果字段
 * @param workMode 本次任务固定的工作模式，不从工具参数获取
 * @param permissions 当前会话审批服务；跨任务复用文件授权，不跨工作区或历史恢复
 */
public record ToolContext(WorkspaceBinding workspace, int maxOutputChars, WorkMode workMode, PermissionService permissions) {
    /** 首版输出上限，避免工具结果无限增长；具体读取工具仍需限制读取量。 */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 16_000;

    /** 使用默认输出上限构造上下文。 */
    public ToolContext(WorkspaceBinding workspace) {
        this(workspace, DEFAULT_MAX_OUTPUT_CHARS);
    }

    /** 独立库调用默认 ask、无审批输入；写入明确报需审批，不默默放行。 */
    public ToolContext(WorkspaceBinding workspace, int maxOutputChars) {
        this(workspace, maxOutputChars, WorkMode.REACT,
                defaultPermissions(workspace));
    }

    private static PermissionService defaultPermissions(WorkspaceBinding workspace) {
        if (workspace == null) throw new IllegalArgumentException("Workspace binding is required.");
        return new PermissionService(PermissionMode.ASK, workspace, ApprovalHandler.unavailable());
    }

    /** 上下文由宿主创建，必须明确工作区状态和正数输出上限。 */
    public ToolContext {
        if (workspace == null || maxOutputChars <= 0 || workMode == null || permissions == null) {
            throw new IllegalArgumentException("Tool context requires a workspace binding and positive output limit.");
        }
        permissions.checkWorkspace(workspace);
    }

    /** 用于筛选可向模型展示的定义，失效目录不作为可用项目工作区。 */
    public boolean hasWorkspace() {
        return workspace.mode() == WorkspaceBinding.Mode.DIRECTORY && WorkspaceResolver.available(workspace);
    }
}
