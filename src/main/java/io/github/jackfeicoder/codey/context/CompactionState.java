package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageHistory;
import io.github.jackfeicoder.codey.session.MessageRole;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 摘要覆盖原始历史的严格前缀（不含 system）；原始记录保留，摘要只是派生请求状态。 */
public record CompactionState(int coveredMessages, String sourceHash, SummaryDocument summary, String createdAt) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public CompactionState {
        if (coveredMessages < 2 || sourceHash == null || !sourceHash.matches("[0-9a-f]{64}") || summary == null || createdAt == null)
            throw new IllegalArgumentException("Invalid compaction state.");
        Instant.parse(createdAt); summary.json();
    }

    /** 同时验证完整轮次边界和内容指纹，防止摘要错误应用到其他或已修改的记录。 */
    public void validate(List<Message> raw) {
        if (coveredMessages >= raw.size() || raw.get(coveredMessages).role() != MessageRole.USER)
            throw new IllegalArgumentException("Compaction must cover a complete historical prefix and retain recent turns.");
        MessageHistory.validate(raw.subList(0, coveredMessages));
        if (!sourceHash.equals(fingerprint(raw.subList(0, coveredMessages))))
            throw new IllegalArgumentException("Compaction source does not match original history.");
    }

    public static String fingerprint(List<Message> messages) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(messages)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failed) {
            throw new IllegalStateException("Failed to fingerprint compaction source.");
        }
    }
}
