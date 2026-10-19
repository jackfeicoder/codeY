package io.github.jackfeicoder.codey.agent;

import java.util.Locale;

/** 工作模式与审批预设独立；MULTI 预留名称，当前明确拒绝启用。 */
public enum WorkMode {
    REACT, PLAN, MULTI;
    public String value() { return name().toLowerCase(Locale.ROOT); }
    public static WorkMode parse(String value) {
        if (value != null) for (var mode : values()) if (mode.value().equalsIgnoreCase(value.strip())) return mode;
        throw new IllegalArgumentException("工作模式应为 react 或 plan；multi 尚待实现。");
    }
}
