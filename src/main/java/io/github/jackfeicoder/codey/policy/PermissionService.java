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
    /** 成功执行后复用具体联网服务/构建流程；不授予整文件或任意命令权限。 */
    private final java.util.Map<String, String> sessionGrants = new java.util.HashMap<>();
    private final java.util.Map<String, String> taskGrants = new java.util.HashMap<>();
    private String trustedProjectSnapshot;
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
    public int grantCount() { return sessionGrants.size() + taskGrants.size(); }
    public boolean projectTrusted() { return trustedProjectSnapshot != null
            && trustedProjectSnapshot.equals(CommandImpactClassifier.configurationFingerprint(root)); }
    /** 仅用户 CLI 显式确认后调用；模型没有可调用的信任工具。 */
    public void trustProject() {
        if (root == null) throw denied("No workspace to trust.");
        trustedProjectSnapshot = CommandImpactClassifier.configurationFingerprint(root);
        if (trustedProjectSnapshot == null) throw denied("Project configuration could not be fingerprinted within limits.");
    }
    public void beginTask() { taskGrants.clear(); }
    public void endTask() { taskGrants.clear(); }
    public int denialCount() { return denials.size() + unknownDenials.size(); }
    /** 旧嵌入入口兼容；新版没有整文件授权，使用 grantedScopes 查看实际范围。 */
    public java.util.List<String> grantedFiles() {
        return java.util.List.of();
    }
    public java.util.List<String> grantedScopes() {
        return java.util.stream.Stream.concat(sessionGrants.values().stream(), taskGrants.values().stream()).distinct().sorted().toList();
    }
    /** 用户撤销时同时清除拒绝记录，以便明确重新请求；不接受模型触发。 */
    public void revoke() { sessionGrants.clear(); taskGrants.clear(); denials.clear(); unknownDenials.clear(); trustedProjectSnapshot = null; }

    /** 校验宿主上下文与授权服务属于同一工作区，防止手工复用错误上下文。 */
    public void checkWorkspace(WorkspaceBinding workspace) {
        Path requested = workspace.mode() == WorkspaceBinding.Mode.NONE ? null : Path.of(workspace.path());
        if (!Objects.equals(root, requested)) throw denied("Permission state belongs to another workspace.");
    }

    /** 批准只允许已准备的操作/展示过的具体范围；绝不提升为整个文件的写入权限。 */
    public void authorize(ApprovalRequest request) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (request.target() != null && !Objects.equals(root, request.root())) throw denied("Approval target belongs to another workspace.");
        if (mode == PermissionMode.FULL) return;
        if ((request.target() != null && denials.contains(request.target())) || unknownDenials.contains(request.denialKey())) {
            throw denied("This action was denied in this session. Ask the user to revoke or change permissions; do not retry indirectly.");
        }
        var impact = request.impact();
        boolean reusable = !request.sensitive() && !request.destructive() && impact.reusable();
        if (reusable && (impact.taskScope() ? taskGrants : sessionGrants).containsKey(impact.reuseKey())) return;
        // ask 的普通工作区操作/受限查询直接通过；联网先确认服务/域名范围。
        if (mode == PermissionMode.ASK && !request.sensitive() && !request.destructive() && impact.routine()
                && impact.kind() != OperationImpact.Kind.PUBLIC_NETWORK) return;
        if (mode == PermissionMode.AUTO && (impact.kind() != OperationImpact.Kind.PROJECT_BUILD || projectTrusted())) {
            try { if (reviewer.review(request) == ApprovalReviewer.Verdict.ALLOW) return; }
            catch (RuntimeException unavailable) { /* 审查失败明确回退到人工，而不是允许执行。 */ }
        }
        ApprovalHandler.Answer answer;
        try { answer = handler.approve(request.reviewScope(false)); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (answer == ApprovalHandler.Answer.APPROVE) return;
        if (answer == ApprovalHandler.Answer.DENY) {
            if (request.target() == null) unknownDenials.add(request.denialKey()); else denials.add(request.target());
            throw denied("User denied this action. Do not bypass it with another tool or indirect operation.");
        }
        throw unavailable();
    }

    /** 危险操作永不复用；失败不留批准。构建流程只在当前任务且指纹一致时复用。 */
    public void completed(ApprovalRequest request) {
        var impact = request.impact();
        if (mode != PermissionMode.FULL && !request.sensitive() && !request.destructive() && impact.reusable())
            (impact.taskScope() ? taskGrants : sessionGrants).put(impact.reuseKey(), impact.reason());
    }
    private static ToolException denied(String message) { return new ToolException(ErrorCode.PERMISSION_DENIED, message); }
    private static ToolException unavailable() { return new ToolException(ErrorCode.APPROVAL_REQUIRED,
            "Approval required but no interactive approval is available. Use an interactive terminal, explicitly enable piped approvals, or choose auto/full yourself."); }
}
