package io.github.jackfeicoder.codey.policy;

import java.nio.file.Path;
import java.util.Objects;

/** 已准备操作的元数据；文件预览有界且敏感正文隐藏，命令按实际 argv 逐项展示供人工确认。 */
public record ApprovalRequest(String tool, Path root, Path target, String operation,
                              String beforeHash, String afterHash, long beforeBytes, long afterBytes,
                              boolean sensitive, boolean destructive, String preview, boolean fileGrant, String actionKey,
                              OperationImpact impact) {
    /** 兼容旧扩展工具；只有内置文件/联网操作有默认分类，其余保持未知。 */
    public ApprovalRequest(String tool, Path root, Path target, String operation, String beforeHash, String afterHash,
                           long beforeBytes, long afterBytes, boolean sensitive, boolean destructive, String preview,
                           boolean fileGrant, String actionKey) {
        this(tool, root, target, operation, beforeHash, afterHash, beforeBytes, afterBytes, sensitive, destructive,
                preview, fileGrant, actionKey, defaultImpact(tool, target, operation));
    }
    /** 保留文件工具构造入口；命令附加规范化操作摘要，不与文件授权共用。 */
    public ApprovalRequest(String tool, Path root, Path target, String operation, String beforeHash, String afterHash,
                           long beforeBytes, long afterBytes, boolean sensitive, boolean destructive, String preview, boolean fileGrant) {
        this(tool, root, target, operation, beforeHash, afterHash, beforeBytes, afterBytes,
                sensitive, destructive, preview, fileGrant, null);
    }
    public ApprovalRequest {
        Objects.requireNonNull(tool); Objects.requireNonNull(operation); Objects.requireNonNull(preview); Objects.requireNonNull(impact);
        if ((root == null) != (target == null) || beforeBytes < 0 || afterBytes < 0) throw new IllegalArgumentException("Invalid approval request.");
        if (root != null && (!root.isAbsolute() || !target.isAbsolute() || !root.equals(root.normalize())
                || !target.equals(target.normalize()) || !target.startsWith(root) || target.equals(root))) {
            throw new IllegalArgumentException("Approval target must be a normalized file within its root.");
        }
    }
    /** 文件权限授权按这个规范化目标保存；不同工作区、不同文件不会共用。 */
    public String displayTarget() { return target == null ? (webFetch()?"网页读取":webSearch()?"互联网搜索":command() ? "本地命令" : "外部操作") : root.relativize(target).toString().replace('\\', '/'); }
    /** 此类别只由可信工具构造，不由模型的参数或批准文本决定。 */
    public boolean command() { return "execute_command".equals(operation); }
    /** 固定内置搜索类别，不授权任意联网或 MCP。 */
    public boolean webSearch() {return "web_search".equals(tool) && "web_search".equals(operation) && target==null;}
    public boolean webFetch() {return "web_fetch".equals(tool) && "web_fetch".equals(operation) && target==null;}
    public String denialKey() { return tool + (actionKey == null ? "" : ":" + actionKey); }
    /** 不再授予整文件权限；具体作用域始终来自宿主影响判断。保留参数兼容旧调用。 */
    public ApprovalRequest reviewScope(boolean grantFile) {
        return new ApprovalRequest(tool, root, target, operation, beforeHash, afterHash,
                beforeBytes, afterBytes, sensitive, destructive, preview, false, actionKey, impact);
    }
    public ApprovalRequest withImpact(OperationImpact value) {
        return new ApprovalRequest(tool, root, target, operation, beforeHash, afterHash, beforeBytes, afterBytes,
                sensitive, destructive, preview, false, actionKey, value);
    }
    private static OperationImpact defaultImpact(String tool, Path target, String operation) {
        if (target != null && (tool.equals("write_file") || tool.equals("edit_file")))
            return OperationImpact.of(OperationImpact.Kind.WORKSPACE_EDIT, "工作区文本文件修改，仍检查敏感路径与破坏性变化。");
        if ((tool.equals("web_search") && operation.equals("web_search")) || (tool.equals("web_fetch") && operation.equals("web_fetch")))
            return OperationImpact.of(OperationImpact.Kind.PUBLIC_NETWORK, "向已配置服务发送公开查询或读取公开网页。");
        return OperationImpact.unknown();
    }
    /** 未分类扩展工具仍需审批；没有文件目标时不建立文件级授权。 */
    public static ApprovalRequest unknown(String name) {
        return new ApprovalRequest(name, null, null, "unknown", null, null, 0, 0, false, false, "未分类工具，需人工确认实际用途。", false);
    }
}
