package io.github.jackfeicoder.codey.memory;

import java.time.Instant;
import java.util.List;

/** 一次整理操作保留的完整原条目及其替代版本；归档不参与正常检索。 */
public record MemoryArchive(String id, String kind, String reason, String createdAt,
                            List<MemoryEntry> originals, MemoryEntry replacement) {
    public MemoryArchive {
        if (!MemoryEntry.validId(id) || !List.of("MERGE", "SHORTEN", "ARCHIVE").contains(kind)
                || reason == null || reason.isBlank() || reason.length() > 500 || originals == null
                || originals.isEmpty() || originals.size() > 20 || replacement == null)
            throw new MemoryException("Invalid memory archive.");
        Instant.parse(createdAt); originals = List.copyOf(originals);
    }
}
