package io.github.jackfeicoder.codey.memory;

import java.time.Instant;
import java.util.List;

/** 可恢复的范围级快照；没有嵌套versions，防止递归膨胀。 */
public record MemoryVersion(String id, String createdAt, String reason,
                            List<MemoryEntry> entries, List<MemoryArchive> archived) {
    public MemoryVersion {
        if (!MemoryEntry.validId(id) || reason == null || reason.isBlank() || reason.length() > 500
                || entries == null || entries.size() > 1000 || archived == null || archived.size() > 2000)
            throw new MemoryException("Invalid memory version.");
        Instant.parse(createdAt); entries = List.copyOf(entries); archived = List.copyOf(archived);
    }
}
