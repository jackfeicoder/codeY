package io.github.jackfeicoder.codey.llm;

import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageRole;
import io.github.jackfeicoder.codey.tool.ToolCall;

import java.util.List;

/**
 * 客户端解析完成后的模型结果，交给 CLI 展示或 Agent 决定下一步。
 * @param message 模型的 assistant 消息，包含回答文本或工具调用请求
 * @param finishReason 结束原因，例如 stop、tool_calls、length
 * @param usage Token 用量；对象或字段为 null 表示未知，不视为零
 */
public record LlmResponse(Message message, String finishReason, TokenUsage usage) {
    /** 保证响应来自 assistant 且具有结束原因，防止残缺结果进入后续流程。 */
    public LlmResponse {
        if (message == null || message.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("LLM response requires an assistant message.");
        }
        if (finishReason == null || finishReason.isBlank()) {
            throw new IllegalArgumentException("LLM response finish reason is required.");
        }
    }

    /** 获取可展示的文本；纯工具调用没有文本时返回空字符串。 */
    public String text() {
        return message.content() == null ? "" : message.content();
    }

    /** 获取模型提出的工具请求；调用方需另行执行和回填结果。 */
    public List<ToolCall> toolCalls() {
        return message.toolCalls();
    }

    /** 判断是否因长度限制结束，便于提示用户或触发后续继续生成策略。 */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }

    /**
     * 服务商报告的 Token 计数，使用 Long 允许表达缺失值。
     * @param promptTokens 输入 Token 数
     * @param completionTokens 输出 Token 数
     * @param totalTokens 总 Token 数，保留服务商报告值，不自行推算
     */
    public record TokenUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
        /** 缺失字段可为空，存在的计数必须非负。 */
        public TokenUsage {
            if ((promptTokens != null && promptTokens < 0)
                    || (completionTokens != null && completionTokens < 0)
                    || (totalTokens != null && totalTokens < 0)) {
                throw new IllegalArgumentException("Token counts must be nonnegative when supplied.");
            }
        }
    }
}
