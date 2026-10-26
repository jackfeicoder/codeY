package io.github.jackfeicoder.codey.agent;

/** Agent 请求的展示回调；分片不是已完成消息，不进入历史，也不能触发工具。 */
@FunctionalInterface
public interface AgentStreamListener {
    void onText(String delta);
    /** 请求开始与结束用于终端分区；结束回调也覆盖异常，成功与否仍由 AgentResult/异常判断。 */
    default void onResponseStart(int step) { }
    default void onResponseEnd(int step) { }
}
