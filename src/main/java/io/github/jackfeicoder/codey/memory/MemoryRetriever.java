package io.github.jackfeicoder.codey.memory;

import java.util.List;
/** 只排序调用方提供的作用域内候选，不读其他项目、不请求模型。 */
public interface MemoryRetriever {
    record Hit(MemoryEntry entry, double score) { }
    List<Hit> search(String query, List<MemoryEntry> candidates);
}
