package io.github.jackfeicoder.codey.memory;

import java.time.Instant;

/** 整理尝试的水位与有时限占位；同指纹不重试，变化后还需冷却/增量门控。 */
public record MemoryTidyCheckpoint(String runId, String attemptedAt, String leaseUntil, boolean running,
                                   String fingerprint, int baselineCount, long baselineTokens) {
    public MemoryTidyCheckpoint {
        if (!MemoryEntry.validId(runId) || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")
                || baselineCount < 0 || baselineCount > 1000 || baselineTokens < 0)
            throw new MemoryException("Invalid memory tidy checkpoint.");
        if (Instant.parse(leaseUntil).isBefore(Instant.parse(attemptedAt))) throw new MemoryException("Invalid memory tidy lease.");
    }
}
