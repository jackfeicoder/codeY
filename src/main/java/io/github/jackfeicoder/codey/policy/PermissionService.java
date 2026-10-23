package io.github.jackfeicoder.codey.policy;

import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult.ErrorCode;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 当前会话的审批协调器；单线程使用，不持久化批准/拒绝，不持有文件正文。 */
public final class PermissionService {
    /** 模式在本服务生命周期内固定，切换时由 CLI 创建新服务。 */
    private final PermissionMode mode;
    /** 人工交互和独立自动审查分别注入，不依赖模型生成的批准文本。 */
    private final ApprovalHandler handler;
    private final ApprovalReviewer reviewer;
    /** 每个服务固定到一个真实工作区；普通聊天为 null。 */
    private final Path root;
    /** 成功后批准的文件和用户拒绝的文件，只在当前会话有效。 */
    private final Set<Path> grants = new HashSet<>();
    private final Set<Path> denials = new HashSet<>();
    /** 未分类工具按名称缓存拒绝；命令按真实程序、argv 和 cwd 摘要区分，不授予进程长期权限。 */
    private final Set<String> unknownDenials = new HashSet<>();

    public PermissionService(PermissionMode mode, WorkspaceBinding workspace, ApprovalHandler handler) {
        this(mode, workspace, handler, ApprovalReviewer.rules());
    }
    /** reviewer 可替换但不复用主 Agent 的自批准回复。 */
    public PermissionService(PermissionMode mode, WorkspaceBinding workspace, ApprovalHandler handler, ApprovalReviewer reviewer) {
        this.mode = Objects.requireNonNull(mode); this.handler = Objects.requireNonNull(handler);
        this.reviewer = Objects.requireNonNull(reviewer); Objects.requireNonNull(workspace);
        root = workspace.mode() == WorkspaceBinding.Mode.NONE ? null : Path.of(workspace.path());
    }
    public PermissionMode mode() { return mode; }
    public int grantCount() { return grants.size(); }
    public int denialCount() { return denials.size() + unknownDenials.size(); }
    /** 只返回相对路径快照，供用户查看和撤销授权，不暴露文件正文。 */
    public java.util.List<String> grantedFiles() {
        return grants.stream().map(path -> root.relativize(path).toString().replace('\\', '/')).sorted().toList();
    }
    /** 用户撤销时同时清除拒绝记录，以便明确重新请求；不接受模型触发。 */
    public void revoke() { grants.clear(); denials.clear(); unknownDenials.clear(); }

    /** 校验宿主上下文与授权服务属于同一工作区，防止手工复用错误上下文。 */
    public void checkWorkspace(WorkspaceBinding workspace) {
        Path requested = workspace.mode() == WorkspaceBinding.Mode.NONE ? null : Path.of(workspace.path());
        if (!Objects.equals(root, requested)) throw denied("Permission state belongs to another workspace.");
    }

    /** 批准只允许本次准备好的操作继续，成功完成后再写入文件级授权。 */
    public void authorize(ApprovalRequest request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (request.target() != null && !Objects.equals(root, request.root())) throw denied("Approval target belongs to another workspace.");
        if (mode == PermissionMode.FULL) return;
        if ((request.target() != null && denials.contains(request.target())) || unknownDenials.contains(request.denialKey())) {
            throw denied("This action was denied in this session. Ask the user to revoke or change permissions; do not retry indirectly.");
        }
        if (mode == PermissionMode.ASK && request.target() != null && grants.contains(request.target())) return;
        if (mode == PermissionMode.AUTO && !request.command()) {
            try { if (reviewer.review(request) == ApprovalReviewer.Verdict.ALLOW) return; }
            catch (RuntimeException unavailable) { /* 审查失败明确回退到人工，而不是允许执行。 */ }
        }
        ApprovalHandler.Answer answer;
        try { answer = handler.approve(request.reviewScope(mode == PermissionMode.ASK)); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (answer == ApprovalHandler.Answer.APPROVE) return;
        if (answer == ApprovalHandler.Answer.DENY) {
            if (request.target() == null) unknownDenials.add(request.denialKey()); else denials.add(request.target());
            throw denied("User denied this action. Do not bypass it with another tool or indirect operation.");
        }
        throw unavailable();
    }

    /** 仅 ask 在执行成功后保留文件授权；auto 每次重审，冲突/失败不留下首次批准。 */
    public void completed(ApprovalRequest request) {
        if (request.target() != null && mode == PermissionMode.ASK) grants.add(request.target());
    }
    private static ToolException denied(String message) { return new ToolException(ErrorCode.PERMISSION_DENIED, message); }
    private static ToolException unavailable() { return new ToolException(ErrorCode.APPROVAL_REQUIRED,
            "Approval required but no interactive approval is available. Use an interactive terminal, explicitly enable piped approvals, or choose auto/full yourself."); }
}
