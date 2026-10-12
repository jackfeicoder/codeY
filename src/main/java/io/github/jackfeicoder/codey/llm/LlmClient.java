package io.github.jackfeicoder.codey.llm;

/** 模型调用契约。CLI 与后续 Agent 面向此接口，具体协议由实现类处理。 */
public interface LlmClient {
    /**
     * 发送一次请求并返回完整结果；当前实现为非流式调用。
     * @param request 本轮消息列表
     * @return 模型消息、结束原因与 Token 用量
     * @throws LlmException 请求、网络或响应处理失败
     */
    LlmResponse generate(LlmRequest request);
}
