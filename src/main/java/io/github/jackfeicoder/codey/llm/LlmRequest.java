package io.github.jackfeicoder.codey.llm;

import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolDefinition;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 本轮发送给模型的数据对象；模型名、地址与超时由客户端绑定的 LlmConfig 提供。
 * @param messages 按对话顺序排列的完整消息列表，内部保存不可修改的副本
 * @param tools 本次可用工具定义；为空时保持普通聊天协议，不发送 tools 字段
 * @param toolCallsAllowed false 时用于预算收尾，协议显式发送 tool_choice=none（存在工具定义时）
 */
public record LlmRequest(List<Message> messages, List<ToolDefinition> tools, boolean toolCallsAllowed) {
    /** 常规请求允许模型自主选择工具，保留原两参数构造方式。 */
    public LlmRequest(List<Message> messages, List<ToolDefinition> tools) { this(messages, tools, true); }
    /** 普通聊天和标题生成无需工具，保留原来的构造方式。 */
    public LlmRequest(List<Message> messages) { this(messages, List.of()); }

    /** 校验非空消息列表并复制，避免请求创建后被外部改动。 */
    public LlmRequest {
        if (messages == null || messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("LLM request requires a nonempty list of non-null messages.");
        }
        messages = List.copyOf(messages); // record 固定的是字段引用，列表内容仍需主动保护。
        if (tools == null || tools.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("LLM tools must be a non-null list of definitions.");
        }
        var names = new HashSet<String>(); // 同一请求不允许同名工具定义。
        for (ToolDefinition tool : tools) {
            if (!names.add(tool.name())) throw new IllegalArgumentException("Duplicate LLM tool definition.");
        }
        tools = List.copyOf(tools);
    }
}
