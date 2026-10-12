package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.tool.ToolCall;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变消息：会话保存它，后续 LLM 适配器将它转换成请求 JSON。
 * assistant 的 toolCalls 表示调用请求，tool 的 toolCallId 表示对应调用的执行结果。
 * @param role 消息来源：系统、用户、模型或工具
 * @param content 文本内容；有工具调用的 assistant 可为 null
 * @param toolCalls 模型提出的调用列表，仅 assistant 可携带
 * @param toolCallId 当前工具结果对应的调用 ID，仅 tool 可携带
 */
public record Message(MessageRole role, String content, List<ToolCall> toolCalls, String toolCallId) {
    /** 校验单条消息的角色约束，并复制工具列表以保持不可变。 */
    public Message {
        if (role == null) {
            throw new IllegalArgumentException("Message role is required.");
        }
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        if (toolCalls.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Tool calls must not contain null entries.");
        }
        toolCalls = List.copyOf(toolCalls);

        if (role != MessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Only assistant messages may contain tool calls.");
        }
        if (role == MessageRole.TOOL) {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Tool messages require toolCallId.");
            }
        } else if (toolCallId != null) {
            throw new IllegalArgumentException("Only tool messages may contain toolCallId.");
        }
        if (content == null && !(role == MessageRole.ASSISTANT && !toolCalls.isEmpty())) {
            throw new IllegalArgumentException("Message content is required unless assistant requests tools.");
        }
        Set<String> callIds = new HashSet<>(); // 检测同一 assistant 消息内的调用 ID 重复。
        for (ToolCall call : toolCalls) {
            // call 是单个模型调用请求，结果消息通过它的 id 关联。
            if (!callIds.add(call.id())) {
                throw new IllegalArgumentException("Tool call ids must be unique within an assistant message.");
            }
        }
    }

    /** 创建系统指令消息，例如角色、任务边界或项目规则。 */
    public static Message system(String content) {
        return new Message(MessageRole.SYSTEM, content, List.of(), null);
    }

    /** 创建用户输入消息，保持原始文本和换行。 */
    public static Message user(String content) {
        return new Message(MessageRole.USER, content, List.of(), null);
    }

    /** 创建普通模型回答，不携带工具请求。 */
    public static Message assistant(String content) {
        return assistant(content, List.of());
    }

    /** 同时保存说明文本与工具请求，content 在有工具调用时可以为 null。 */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(MessageRole.ASSISTANT, content, toolCalls, null);
    }

    /** 创建只发起工具调用、没有回答文本的模型消息。 */
    public static Message assistantToolCalls(List<ToolCall> toolCalls) {
        return assistant(null, toolCalls);
    }

    /** 输出可以为空字符串，例如读取到空文件，但关联调用 ID 必须存在。 */
    public static Message tool(String toolCallId, String content) {
        return new Message(MessageRole.TOOL, content, List.of(), toolCallId);
    }
}
