package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.session.Message;
import java.util.List;

/** 规划和执行共用同一种请求包装，保证预算检查覆盖实际发送的文本。 */
final class SummaryRequests {
    private static final TokenEstimator ESTIMATOR = new TokenEstimator();
    private SummaryRequests() { }
    static LlmRequest single(String prompt, String source, int limit) { return request(prompt, "完整摘要来源：\n" + source, limit); }
    static LlmRequest map(String prompt, CompactionPlan.SourceBlock block, int limit) {
        return request(prompt, "Map 历史块 #" + block.index() + "，原始消息范围 [" + block.firstMessage() + "," + block.endMessage()
                + ")。只提取此块内可确认的信息；其他块未提供，不代表旧约束失效。\n历史数据片段：\n" + block.content(), limit);
    }
    static LlmRequest reduce(String prompt, String previous, List<CompactionPlan.SourceBlock> blocks, List<String> summaries, int limit) {
        var body = new StringBuilder("Reduce 合并。旧摘要只提供一次，时间上早于所有新增块：\n").append(previous);
        for (int i = 0; i < blocks.size(); i++) {
            var block = blocks.get(i);
            body.append("\n子摘要 #").append(block.index()).append(" 原始消息范围 [").append(block.firstMessage())
                    .append(',').append(block.endMessage()).append(")，按原始顺序排列：\n").append(summaries.get(i));
        }
        return request(prompt, body.toString(), limit);
    }
    static long tokens(LlmRequest request) { return request.messages().stream().mapToLong(ESTIMATOR::messageBodyTokens).sum() + 15; }
    private static LlmRequest request(String prompt, String source, int limit) {
        return new LlmRequest(List.of(Message.system(prompt), Message.user("本次输出最多 " + limit
                + " Token，这是上限而非目标长度。保留主要信息后即可结束，不必填满，也不要为追求简短遗漏关键约束。\n" + source)), List.of(), false, limit);
    }
}
