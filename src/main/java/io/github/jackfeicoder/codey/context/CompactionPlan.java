package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.llm.LlmRequest;
import java.util.List;

/**
 * 一次压缩的只读执行计划；不保存到磁盘，不持有 Session 或执行工具。
 * @param strategy Single 或 MapReduce，按来源大小及单次预算选择
 * @param sourceTokens 原始摘要来源的本地估算量，不是全部会话占用
 * @param singleRequest Single 请求；MapReduce 时为 null
 * @param blocks 按原始顺序排列的来源块；Single 时为空
 * @param previousSummary 旧摘要 JSON，只提供给 Single 或 Reduce，不重复进入各 Map
 * @param mapOutputLimit 每个 Map 的输出上限，所有子摘要都能进入一次 Reduce
 * @param finalOutputLimit 最终摘要动态额度，独立于普通回答预留
 * @param reduceInputBudget 扣除允许输出及安全余量后的模型输入预算
 */
public record CompactionPlan(Strategy strategy, long sourceTokens, LlmRequest singleRequest,
                             List<SourceBlock> blocks, String previousSummary, int mapOutputLimit,
                             int finalOutputLimit, long reduceInputBudget) {
    public enum Strategy { SINGLE, MAP_REDUCE }
    /** 消息下标基于不含 system 的原始历史，范围左闭右开；content 含片段来源标记。 */
    public record SourceBlock(int index, int firstMessage, int endMessage, String content) { }
    public CompactionPlan { blocks = List.copyOf(blocks); }
    public int expectedCalls() { return strategy == Strategy.SINGLE ? 1 : blocks.size() + 1; }
}
