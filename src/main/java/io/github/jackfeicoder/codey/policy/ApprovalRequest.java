package io.github.jackfeicoder.codey.policy;

import java.nio.file.Path;
import java.util.Objects;

/** 已准备操作的元数据；文件预览有界且敏感正文隐藏，命令按实际 argv 逐项展示供人工确认。 */
public record ApprovalRequest(String tool, Path root, Path target, String operation,
                              String beforeHash, String afterHash, long beforeBytes, long afterBytes,
                              boolean sensitive, boolean destructive, String preview, boolean fileGrant, String actionKey) {
    /** 保留文件工具构造入口；命令附加规范化操作摘要，不与文件授权共用。 */
    public ApprovalRequest(String tool, Path root, Path target, String operation, String beforeHash, String afterHash,
                           long beforeBytes, long afterBytes, boolean sensitive, boolean destructive, String preview, boolean fileGrant) {
        this(tool, root, target, operation, beforeHash, afterHash, beforeBytes, afterBytes,
                sensitive, destructive, preview, fileGrant, null);
    }
    public ApprovalRequest {
        Objects.requireNonNull(tool); Objects.requireNonNull(operation); Objects.requireNonNull(preview);
        if ((root == null) != (target == null) || beforeBytes < 0 || afterBytes < 0) throw new IllegalArgumentException("Invalid approval request.");
        if (root != null && (!root.isAbsolute() || !target.isAbsolute() || !root.equals(root.normalize())
                || !target.equals(target.normalize()) || !target.startsWith(root) || target.equals(root))) {
            throw new IllegalArgumentException("Approval target must be a normalized file within its root.");
        }
    }
    /** 文件权限授权按这个规范化目标保存；不同工作区、不同文件不会共用。 */
    public String displayTarget() { return target == null ? (command() ? "本地命令（逐次审批）" : "未分类操作") : root.relativize(target).toString().replace('\\', '/'); }
    /** 此类别只由可信工具构造，不由模型的参数或批准文本决定。 */
    public boolean command() { return "execute_command".equals(operation); }
    public String denialKey() { return tool + (actionKey == null ? "" : ":" + actionKey); }
    /** 审批范围由宿主模式决定，不接受模型指定。auto 的风险操作只批准当前一次。 */
    public ApprovalRequest reviewScope(boolean grantFile) {
        return new ApprovalRequest(tool, root, target, operation, beforeHash, afterHash,
                beforeBytes, afterBytes, sensitive, destructive, preview, grantFile && target != null, actionKey);
    }
    /** 未分类扩展工具仍需审批；没有文件目标时不建立文件级授权。 */
    public static ApprovalRequest unknown(String name) {
        return new ApprovalRequest(name, null, null, "unknown", null, null, 0, 0, false, true, "未分类工具，需人工确认实际用途。", false);
    }
}
