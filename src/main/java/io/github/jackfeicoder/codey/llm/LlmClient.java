package io.github.jackfeicoder.codey.llm;

/** 模型调用契约。并行摘要可并发调用同一客户端，实现须隔离请求状态；具体协议由实现类处理。 */
public interface LlmClient {
    /**
     * 发送一次非流式请求并返回完整结果；标题生成等场景可保持静默。
     * @param request 本轮消息列表
     * @return 模型消息、结束原因与 Token 用量
     * @throws LlmException 请求、网络或响应处理失败
     */
    LlmResponse generate(LlmRequest request);

    /** 增量展示正文，仍返回完整响应；旧实现/测试替身默认一次回调，接口保持可用作 lambda。 */
    default LlmResponse generateStream(LlmRequest request, java.util.function.Consumer<String> onText) {
        java.util.Objects.requireNonNull(onText, "onText");
        LlmResponse response = generate(request);
        if (response != null && !response.text().isEmpty()) onText.accept(response.text());
        return response;
    }
}
