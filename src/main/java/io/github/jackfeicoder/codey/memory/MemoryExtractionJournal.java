package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 每个成功用户轮次先独占创建尝试标记，再请求模型；崩溃/失败后不隐式重放提取。 */
public final class MemoryExtractionJournal {
    private final Path root;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public MemoryExtractionJournal(Path dataDirectory) { root = dataDirectory.toAbsolutePath().normalize().resolve("memory/extractions"); }
    /** 轮次位置与原用户输入参与摘要；相同输入的不同轮次仍是不同任务。 */
    public static String turnKey(int messageCount, String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((messageCount + "\n" + input).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failed) { throw new IllegalStateException(failed); }
    }
    public boolean claim(String sessionId, String turnKey) {
        Path file = path(sessionId, turnKey);
        try {
            JsonMemoryStore.checkPath(file); Files.createDirectories(file.getParent()); JsonMemoryStore.checkPath(file);
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                write(channel, payload("CLAIMED", List.of()));
            }
            return true;
        } catch (FileAlreadyExistsException exists) { return false; }
        catch (IOException | SecurityException failed) { throw new MemoryException("记忆提取尝试标记保存失败，跳过提取。"); }
    }
    /** 结果不包含用户原文/模型原文；失败时已存在claim仍阻止重复调用。 */
    public void finish(String sessionId, String turnKey, String status, List<String> savedIds) {
        Path file = path(sessionId, turnKey), temp = null;
        try {
            JsonMemoryStore.checkPath(file);
            temp = Files.createTempFile(file.getParent(), "extraction-", ".tmp");
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { write(channel, payload(status, savedIds)); }
            JsonMemoryStore.checkPath(file);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); temp = null;
        } catch (IOException | SecurityException failed) { throw new MemoryException("提取结果标记未保存；已有尝试标记保留，不重复提取。"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    private static byte[] payload(String status, List<String> ids) throws IOException {
        var data = MAPPER.createObjectNode().put("schemaVersion", 1).put("status", status).put("updatedAt", Instant.now().toString());
        var saved = data.putArray("savedIds"); ids.forEach(saved::add);
        return MAPPER.writeValueAsBytes(data);
    }
    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
    }
    private Path path(String sessionId, String key) {
        if (!MemoryEntry.validId(sessionId) || key == null || !key.matches("[a-f0-9]{64}")) throw new MemoryException("Invalid extraction source identity.");
        return root.resolve(sessionId).resolve(key + ".json");
    }
}
