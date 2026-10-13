package io.github.jackfeicoder.codey.session;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 校验完整对话轮次：user → (assistant 工具调用 → 全部 tool 结果)* → assistant 回答。 */
public final class MessageHistory {
    /** 仅提供顺序校验，不创建可变历史对象。 */
    private MessageHistory() { }

    /** 不含 system 的历史必须由完整轮次组成；空列表表示尚未对话。 */
    public static void validate(List<Message> messages) {
        if (messages == null) throw invalid();
        int index = 0; // 顺序消费消息，校验完成前不修改调用方的数据。
        while (index < messages.size()) {
            Message user = messages.get(index++);
            if (user == null || user.role() != MessageRole.USER || user.content().isBlank()) throw invalid();
            Set<String> seen = new HashSet<>(); // 同一任务中的调用 ID 不重复，跨用户轮次可复用。
            while (true) {
                if (index >= messages.size()) throw invalid();
                Message assistant = messages.get(index++);
                if (assistant == null || assistant.role() != MessageRole.ASSISTANT) throw invalid();
                if (assistant.toolCalls().isEmpty()) break; // 无工具的 assistant 结束本轮。
                Set<String> pending = new HashSet<>();
                for (var call : assistant.toolCalls()) {
                    if (!seen.add(call.id())) throw invalid();
                    pending.add(call.id());
                }
                int results = pending.size();
                for (int result = 0; result < results; result++) {
                    if (index >= messages.size()) throw invalid();
                    Message tool = messages.get(index++);
                    if (tool == null || tool.role() != MessageRole.TOOL || !pending.remove(tool.toolCallId())) {
                        throw invalid();
                    }
                }
            }
        }
    }

    /** 固定错误说明，不回显用户输入或工具内容。 */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("History requires complete turns and paired tool results.");
    }
}
