package io.github.jackfeicoder.codey.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一条独立长期信息；项目常驻规则只在 AGENTS.md 中管理。时间和来源用于追溯，不证明内容正确。 */
public record MemoryEntry(String id, MemoryScope scope, String projectId, MemoryLoading loading,
                          String title, String content, List<String> tags, String sourceSessionId,
                          String createdAt, String updatedAt) {
    public MemoryEntry {
        if (!validId(id) || scope == null || loading == null
                || (scope == MemoryScope.USER && projectId != null)
                || (scope == MemoryScope.PROJECT && (projectId == null || !projectId.matches("[a-f0-9]{64}") || loading == MemoryLoading.ALWAYS))
                || title == null || title.isBlank() || title.length() > 120
                || content == null || content.isBlank() || content.length() > 8192
                || tags == null || tags.size() > 16 || tags.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 40)
                || (sourceSessionId != null && !validId(sourceSessionId)))
            throw new MemoryException("Invalid memory entry; project ALWAYS rules belong in AGENTS.md.");
        tags = List.copyOf(tags);
        try {
            if (Instant.parse(createdAt).isAfter(Instant.parse(updatedAt))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new MemoryException("Invalid memory timestamp."); }
    }
    /** 使用完整 UUID 操作，避免误删短前缀对应的其他条目。 */
    public static boolean validId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
