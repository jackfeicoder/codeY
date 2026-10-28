package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** 有版本的本地 JSON：有界严格读取、独立文件锁、持锁重读与同目录原子替换。 */
public final class JsonMemoryStore implements MemoryStore {
    /** 单个作用域文件的硬资源上限，独立于上下文Token预算。 */
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_ENTRIES = 1000;
    /** 用户数据目录下的memory目录，不位于项目源码。 */
    private final Path root;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 构造不创建目录；未保存记忆的启动不会产生空文件。 */
    public JsonMemoryStore(Path dataDirectory) { root = dataDirectory.toAbsolutePath().normalize().resolve("memory"); }
    /** 磁盘格式单独带版本，不与会话 schema 版本耦合。 */
    public record Document(int schemaVersion, List<MemoryEntry> entries) {
        public Document {
            if (schemaVersion != 1 || entries == null || entries.size() > MAX_ENTRIES)
                throw new MemoryException("Invalid memory document.");
            entries = List.copyOf(entries);
        }
    }
    @Override public List<MemoryEntry> load(MemoryScope scope, String projectId) {
        Path file = file(scope, projectId);
        try {
            checkPath(file);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            byte[] bytes;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException();
            Document document = mapper.readValue(bytes, Document.class);
            if (document == null) throw new IOException();
            return checked(document.entries(), scope, projectId);
        } catch (IOException | RuntimeException failed) { throw new MemoryException("记忆文件损坏、超限或读取失败；原文件保留，请检查 memory 存储。"); }
    }
    @Override public List<MemoryEntry> update(MemoryScope scope, String projectId, UnaryOperator<List<MemoryEntry>> mutation) {
        Path file = file(scope, projectId);
        Path temp = null;
        try {
            checkPath(file);
            Files.createDirectories(file.getParent());
            checkPath(file);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            checkPath(lockFile);
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new MemoryException("记忆正由其他进程更新，请重试。");
                List<MemoryEntry> entries = checked(mutation.apply(load(scope, projectId)), scope, projectId);
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Document(1, entries));
                if (bytes.length > MAX_BYTES) throw new MemoryException("记忆存储超过 1MiB，请先清理条目。");
                temp = Files.createTempFile(file.getParent(), "memory-", ".tmp");
                try (var out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) out.write(buffer);
                    out.force(true);
                }
                checkPath(file);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                temp = null;
                return entries;
            }
        } catch (MemoryException known) { throw known; }
        catch (java.nio.channels.OverlappingFileLockException busy) { throw new MemoryException("记忆正由其他会话更新，请重试。"); }
        catch (IOException | SecurityException failed) { throw new MemoryException("记忆保存失败，原文件保留；请检查目录权限与原子替换支持。"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    /** 文件名只由枚举与摘要生成，不接受用户标题或模型路径。 */
    private Path file(MemoryScope scope, String projectId) {
        if (scope == MemoryScope.USER && projectId == null) return root.resolve("user.json");
        if (scope == MemoryScope.PROJECT && projectId != null && projectId.matches("[a-f0-9]{64}"))
            return root.resolve("projects").resolve(projectId + ".json");
        throw new MemoryException("Invalid memory scope.");
    }
    /** 检查包括数据目录在内的已有路径段，拒绝符号链接或目录重定向。 */
    private static void checkPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current.toAbsolutePath().normalize())) throw new IOException();
        }
    }
    private static List<MemoryEntry> checked(List<MemoryEntry> entries, MemoryScope scope, String projectId) {
        if (entries == null || entries.size() > MAX_ENTRIES) throw new MemoryException("记忆条目超过上限。");
        Set<String> ids = new HashSet<>();
        for (var entry : entries) {
            if (entry == null || entry.scope() != scope || !Objects.equals(entry.projectId(), projectId) || !ids.add(entry.id()))
                throw new MemoryException("记忆作用域或 ID 校验失败。");
        }
        return List.copyOf(entries);
    }
}
