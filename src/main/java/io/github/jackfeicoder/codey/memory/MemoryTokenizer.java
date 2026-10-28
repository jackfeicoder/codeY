package io.github.jackfeicoder.codey.memory;

import java.util.List;

/** 记忆查询、标题、标签与正文共用的分词入口；不等于LLM的Token计费分词器。 */
@FunctionalInterface
public interface MemoryTokenizer {
    List<String> tokenize(String text);
}
