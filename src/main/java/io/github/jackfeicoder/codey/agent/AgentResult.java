package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageHistory;

import java.util.List;

/**
 * 一条用户任务的结果；只有 COMPLETED 可提交会话，预算耗尽的轨迹仅供当前运行查看。
 * @param status 正常结束或模型请求次数耗尽
 * @param turnMessages 本轮 user、assistant/tool 与最终回答，不含旧历史和 system
 * @param response 最终模型响应；预算耗尽时为 null
 * @param modelCalls 本轮实际模型请求次数
 * @param toolCalls 本轮执行器调度次数，包括未知工具、校验失败和执行失败
 */
public record AgentResult(Status status, List<Message> turnMessages, LlmResponse response,
                          int modelCalls, int toolCalls) {
    /** 明确区分完成与预算退出，不把中间工具请求当最终回答。 */
    public enum Status { COMPLETED, BUDGET_EXHAUSTED }

    /** 固定轨迹快照，完成结果必须具有可保存的完整消息组。 */
    public AgentResult {
        if (status == null || turnMessages == null || turnMessages.isEmpty() || modelCalls < 1 || toolCalls < 0) {
            throw new IllegalArgumentException("Invalid agent result.");
        }
        turnMessages = List.copyOf(turnMessages);
        if (status == Status.COMPLETED) {
            MessageHistory.validate(turnMessages);
            if (response == null || !response.toolCalls().isEmpty()
                    || !turnMessages.get(turnMessages.size() - 1).equals(response.message())) {
                throw new IllegalArgumentException("Completed agent result requires a final response.");
            }
        } else if (response != null) {
            throw new IllegalArgumentException("Exhausted agent result has no final response.");
        }
    }

    /** CLI 据此决定提交还是报告未完成。 */
    public boolean completed() { return status == Status.COMPLETED; }
}
