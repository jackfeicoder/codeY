package io.github.jackfeicoder.codey.policy;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.tool.ToolAccess;
import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult.ErrorCode;

/** 工作模式先于审批；full 不能取消 PLAN 的只读约束。 */
public final class PermissionPolicy {
    private PermissionPolicy() { }
    public static void check(WorkMode mode, ToolAccess access) {
        if (mode == WorkMode.MULTI) throw new ToolException(ErrorCode.PERMISSION_DENIED, "Multi-agent execution is not implemented.");
        if (mode == WorkMode.PLAN && access != ToolAccess.READ_ONLY) {
            throw new ToolException(ErrorCode.PERMISSION_DENIED, "PLAN only allows trusted read-only tools. Switch to react to modify files.");
        }
    }
}
