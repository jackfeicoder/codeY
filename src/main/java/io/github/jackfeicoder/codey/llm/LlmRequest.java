package io.github.jackfeicoder.codey.llm;

import io.github.jackfeicoder.codey.session.Message;

import java.util.List;
import java.util.Objects;

/**
 * 本轮发送给模型的数据对象；模型名、地址与超时由客户端绑定的 LlmConfig 提供。
 * @param messages 按对话顺序排列的完整消息列表，内部保存不可修改的副本
 */
public record LlmRequest(List<Message> messages) {
    /** 校验非空消息列表并复制，避免请求创建后被外部改动。 */
    public LlmRequest {
        if (messages == null || messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("LLM request requires a nonempty list of non-null messages.");
        }
        messages = List.copyOf(messages); // record 固定的是字段引用，列表内容仍需主动保护。
    }
}
