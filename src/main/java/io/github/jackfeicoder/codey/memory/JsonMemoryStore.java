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

/** v1兼容读取；v2把有效条目、归档、最近快照及整理水位放在同一原子事务中。 */
public final class JsonMemoryStore implements MemoryStore {
    /** 全文档硬上限；有效条目仍单独限制1MiB/1000条。 */
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ACTIVE_BYTES = 1024 * 1024;
    public static final int MAX_ENTRIES = 1000;
    public static final int MAX_VERSIONS = 5;
    private final Path root;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public JsonMemoryStore(Path dataDirectory) { root = dataDirectory.toAbsolutePath().normalize().resolve("memory"); }

    public record Document(int schemaVersion, List<MemoryEntry> entries, long revision,
                           List<MemoryArchive> archived, List<MemoryVersion> versions, MemoryTidyCheckpoint checkpoint) {
        /** 原有调用与v1文件仍可使用，首次变更升级到v2。 */
        public Document(int schemaVersion, List<MemoryEntry> entries) { this(schemaVersion, entries, 0, null, null, null); }
        public Document {
            if (entries == null || entries.size() > MAX_ENTRIES || revision < 0 || revision == Long.MAX_VALUE
                    || schemaVersion != 1 && schemaVersion != 2)
                throw new MemoryException("Invalid memory document.");
            if (schemaVersion == 1) {
                if (revision != 0 || checkpoint != null || archived != null && !archived.isEmpty() || versions != null && !versions.isEmpty())
                    throw new MemoryException("Invalid legacy memory document.");
                archived = List.of(); versions = List.of();
            } else if (revision == 0 || archived == null || versions == null || archived.size() > 2000 || versions.size() > MAX_VERSIONS)
                throw new MemoryException("Invalid memory document history.");
            entries = List.copyOf(entries); archived = List.copyOf(archived); versions = List.copyOf(versions);
        }
        /** 每次变更递增revision，包括整理占位，过期模型结果不能覆盖新写入。 */
        public Document next(List<MemoryEntry> active, List<MemoryArchive> archive,
                             List<MemoryVersion> history, MemoryTidyCheckpoint point) {
            return new Document(2, active, revision + 1, archive, history, point);
        }
    }
    @Override public List<MemoryEntry> load(MemoryScope scope, String projectId) { return readDocument(scope, projectId).entries(); }
    public Document readDocument(MemoryScope scope, String projectId) {
        Path file = file(scope, projectId);
        try {
            checkPath(file);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Document(1, List.of());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            byte[] bytes;
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException();
            Document document = mapper.readValue(bytes, Document.class);
            if (document == null) throw new IOException();
            validate(document, scope, projectId); return document;
        } catch (IOException | RuntimeException failed) { throw new MemoryException("记忆文件损坏、超限或读取失败；原文件保留，请检查 memory 存储。"); }
    }
    @Override public List<MemoryEntry> update(MemoryScope scope, String projectId, UnaryOperator<List<MemoryEntry>> mutation) {
        return transact(scope, projectId, old -> old.next(mutation.apply(old.entries()), old.archived(), old.versions(), old.checkpoint())).entries();
    }
    /** 整个范围文件持锁重读和原子提交；mutation抛错或返回原实例时不改文件。 */
    public Document transact(MemoryScope scope, String projectId, UnaryOperator<Document> mutation) {
        Path file = file(scope, projectId), temp = null;
        try {
            checkPath(file); Files.createDirectories(file.getParent()); checkPath(file);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock"); checkPath(lockFile);
            try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new MemoryException("记忆正由其他进程更新，请重试。");
                Document original = readDocument(scope, projectId), document = mutation.apply(original);
                if (document == original) return original;
                if (document == null || document.revision() != original.revision() + 1) throw new MemoryException("记忆版本变更不连续。");
                validate(document, scope, projectId);
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
                if (bytes.length > MAX_BYTES) throw new MemoryException("记忆及恢复版本超过16MiB，原文件保留。");
                temp = Files.createTempFile(file.getParent(), "memory-", ".tmp");
                try (var out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
                }
                checkPath(file); Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); temp = null;
                return document;
            }
        } catch (MemoryException known) { throw known; }
        catch (java.nio.channels.OverlappingFileLockException busy) { throw new MemoryException("记忆正由其他会话更新，请重试。"); }
        catch (IOException | SecurityException failed) { throw new MemoryException("记忆保存失败，原文件保留；请检查目录权限与原子替换支持。"); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
    }
    private void validate(Document document, MemoryScope scope, String project) throws IOException {
        checked(document.entries(), scope, project);
        if (mapper.writeValueAsBytes(document.entries()).length > MAX_ACTIVE_BYTES) throw new MemoryException("有效记忆超过1MiB。");
        checkArchives(document.archived(), scope, project);
        Set<String> versions = new HashSet<>();
        for (var version : document.versions()) {
            if (!versions.add(version.id())) throw new MemoryException("重复记忆版本ID。");
            checked(version.entries(), scope, project); checkArchives(version.archived(), scope, project);
        }
    }
    private static void checkArchives(List<MemoryArchive> archives, MemoryScope scope, String project) {
        Set<String> ids = new HashSet<>();
        for (var archive : archives) {
            if (!ids.add(archive.id())) throw new MemoryException("重复归档ID。");
            checked(archive.originals(), scope, project); checked(List.of(archive.replacement()), scope, project);
        }
    }
    private Path file(MemoryScope scope, String projectId) {
        if (scope == MemoryScope.USER && projectId == null) return root.resolve("user.json");
        if (scope == MemoryScope.PROJECT && projectId != null && projectId.matches("[a-f0-9]{64}")) return root.resolve("projects").resolve(projectId + ".json");
        throw new MemoryException("Invalid memory scope.");
    }
    static void checkPath(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().equals(current.toAbsolutePath().normalize())) throw new IOException();
        }
    }
    private static void checked(List<MemoryEntry> entries, MemoryScope scope, String projectId) {
        if (entries == null || entries.size() > MAX_ENTRIES) throw new MemoryException("记忆条目超过上限。");
        Set<String> ids = new HashSet<>();
        for (var entry : entries) if (entry == null || entry.scope() != scope || !Objects.equals(entry.projectId(), projectId) || !ids.add(entry.id()))
            throw new MemoryException("记忆作用域或ID校验失败。");
    }
}
