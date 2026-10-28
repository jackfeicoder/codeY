package io.github.jackfeicoder.codey.memory;

import java.util.List;

/** 提取结果不是写入指令；REVIEW或纠正只展示候选，不自动覆盖旧条目。 */
public record MemoryCandidate(Action action, MemoryScope scope, String title, String content,
                              List<String> tags, String evidence, String relatedId) {
    public enum Action { ADD, REVIEW }
    public MemoryCandidate {
        if (action == null || scope == null || title == null || title.isBlank() || title.length() > 120
                || content == null || content.isBlank() || content.length() > 1000 || tags == null || tags.size() > 8
                || tags.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 40)
                || evidence == null || evidence.isBlank() || evidence.length() > 512
                || relatedId != null && (!MemoryEntry.validId(relatedId) || action == Action.ADD))
            throw new MemoryException("记忆候选结构无效。");
        tags = List.copyOf(tags);
    }
}
