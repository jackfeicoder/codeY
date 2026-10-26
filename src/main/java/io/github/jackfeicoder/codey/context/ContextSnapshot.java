package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.llm.LlmRequest;
import java.util.Objects;

/** 一次请求的不可变视图与用量拆分；不改写 Session，不加入持久化消息或 HTTP 字段。 */
public record ContextSnapshot(LlmRequest request, TokenBreakdown tokens) {
    public ContextSnapshot {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(tokens, "tokens");
    }

    /** 各项互不重复；历史含过去完整工具组，本次 assistant 含正文和 tool_calls 参数。 */
    public record TokenBreakdown(long basePrompt, long runtimeInstructions, long history,
                                 long userInput, long assistant, long toolResults,
                                 long toolDefinitions, long protocolOverhead, long summary) {
        public TokenBreakdown(long basePrompt, long runtimeInstructions, long history, long userInput,
                              long assistant, long toolResults, long toolDefinitions, long protocolOverhead) {
            this(basePrompt, runtimeInstructions, history, userInput, assistant, toolResults, toolDefinitions, protocolOverhead, 0);
        }
        public TokenBreakdown {
            if (basePrompt < 0 || runtimeInstructions < 0 || history < 0 || userInput < 0
                    || assistant < 0 || toolResults < 0 || toolDefinitions < 0 || protocolOverhead < 0 || summary < 0) {
                throw new IllegalArgumentException("Token estimates must be nonnegative.");
            }
        }

        /** 当前请求输入的估算总量；不含尚未生成的模型输出，也不是累计计费量。 */
        public long total() {
            return basePrompt + runtimeInstructions + history + userInput + assistant
                    + toolResults + toolDefinitions + protocolOverhead + summary;
        }
    }
}
