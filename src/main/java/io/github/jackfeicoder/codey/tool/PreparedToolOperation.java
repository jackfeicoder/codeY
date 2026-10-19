package io.github.jackfeicoder.codey.tool;

import io.github.jackfeicoder.codey.policy.ApprovalRequest;
import io.github.jackfeicoder.codey.policy.PermissionPolicy;

import java.util.Objects;

/** 已准备好的操作：封装确定的快照与新内容，审批后不重新解析模型参数。 */
public final class PreparedToolOperation {
    @FunctionalInterface public interface Action { String run() throws InterruptedException; }
    /** null 只用于纯读取/无变化操作，其他情况保留审批元数据。 */
    private final ApprovalRequest approval;
    /** 捕获原快照、新内容及输出，不在审批后读取可变 JSON 参数。 */
    private final Action action;
    public PreparedToolOperation(ApprovalRequest approval, Action action) {
        this.approval = approval; this.action = Objects.requireNonNull(action);
    }
    /** 即使直接调用写工具 execute，也走相同审批；没有请求表示纯读取或无实际改动。 */
    public String execute(ToolContext context) throws InterruptedException {
        context.permissions().checkWorkspace(context.workspace());
        if (approval != null) {
            PermissionPolicy.check(context.workMode(), approval.target() == null ? ToolAccess.UNKNOWN : ToolAccess.FILE_MUTATION);
            context.permissions().authorize(approval);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String result = action.run();
        if (result == null) throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED, "Tool returned no result.");
        if (approval != null) context.permissions().completed(approval);
        return result;
    }
}
