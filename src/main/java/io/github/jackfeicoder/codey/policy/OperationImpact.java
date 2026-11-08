package io.github.jackfeicoder.codey.policy;

import java.util.Objects;

/** 宿主根据实际操作生成的影响说明；模型参数没有批准或风险等级字段。 */
public record OperationImpact(Kind kind, String reason, String reuseKey, boolean taskScope) {
    public enum Kind { WORKSPACE_EDIT, LOCAL_QUERY, PROJECT_BUILD, PUBLIC_NETWORK,
        TRUSTED_CONNECTION, TRUSTED_REMOTE_READ, UNKNOWN }
    public OperationImpact {
        Objects.requireNonNull(kind); Objects.requireNonNull(reason);
        if (reuseKey != null && reuseKey.isBlank()) throw new IllegalArgumentException("Empty approval scope.");
    }
    public static OperationImpact of(Kind kind, String reason) { return new OperationImpact(kind, reason, null, false); }
    public static OperationImpact unknown() { return of(Kind.UNKNOWN, "尚未确认操作影响，交由用户核对。"); }
    /** 只自动放行宿主已经确认的普通操作，构建还需要会话级项目信任。 */
    public boolean routine() { return kind != Kind.UNKNOWN && kind != Kind.PROJECT_BUILD; }
    /** 当前仅联网与项目构建支持范围复用，未知工具不获得隐式长期批准。 */
    public boolean reusable() { return reuseKey != null && (kind == Kind.PUBLIC_NETWORK || kind == Kind.PROJECT_BUILD); }
}
