package io.github.jackfeicoder.codey.session;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

/** 单文件 JSON 快照存储：UUID 目录、严格加载、单写者文件锁和同目录原子替换。 */
public final class JsonSessionStore implements SessionStore {
    /** 上限防止损坏/异常记录一次性占用过多内存，超限不覆盖原文件。 */
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    /** 所有会话集中存放，不随工作区切换。 */
    private final Path root;
    /** 独立磁盘映射器，不复用模型协议的 wire 字段映射。 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** dataDirectory 是用户目录 .codey 或显式 CODEY_DATA_DIR；此时不创建磁盘目录。 */
    public JsonSessionStore(Path dataDirectory) {
        root = dataDirectory.toAbsolutePath().normalize().resolve("sessions");
    }

    /** 遍历合法会话目录，最近更新时间优先；空会话不列出。 */
    @Override
    public Listing list() {
        var sessions = new ArrayList<SessionSnapshot>();
        var warnings = new ArrayList<String>();
        if (!Files.exists(root)) {
            return new Listing(sessions, warnings);
        }
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String id = path.getFileName().toString();
                if (!SessionSnapshot.validId(id)) {
                    continue;
                }
                try {
                    SessionSnapshot snapshot = load(id);
                    if (!snapshot.messages().isEmpty()) {
                        sessions.add(snapshot);
                    }
                } catch (SessionException invalid) {
                    warnings.add(invalid.getMessage());
                }
            }
        } catch (IOException failed) {
            throw new SessionException("Failed to list session storage.");
        }
        sessions.sort(Comparator.comparing((SessionSnapshot s) -> Instant.parse(s.updatedAt())).reversed()
                .thenComparing(SessionSnapshot::id));
        return new Listing(sessions, warnings);
    }

    /** 读取并验证版本、ID、提示词摘要和消息结构；错误不回显 JSON 正文。 */
    @Override
    public SessionSnapshot load(String id) {
        Path file = directory(id).resolve("session.json");
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BYTES) {
                throw new IOException();
            }
            byte[] data;
            // 有界读取，也覆盖检查大小后文件被外部增长的情况。
            try (var stream = Files.newInputStream(file)) {
                data = stream.readNBytes(MAX_BYTES + 1);
            }
            if (data.length > MAX_BYTES) {
                throw new IOException();
            }
            SessionSnapshot snapshot = mapper.readValue(data, SessionSnapshot.class);
            if (snapshot == null || !snapshot.id().equals(id)) {
                throw new IOException();
            }
            return snapshot;
        } catch (IOException | RuntimeException failed) {
            throw new SessionException("Invalid or unreadable session: " + id);
        }
    }

    /** 锁文件独立于原子替换的数据文件，避免替换后锁失效。 */
    @Override
    public Lease lock(String id, boolean create) {
        Path directory = directory(id);
        FileChannel channel = null;
        FileLock lock = null;
        try {
            if (create) {
                Files.createDirectories(root);
                Files.createDirectory(directory);
            } else if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException();
            }
            Path lockPath = directory.resolve("session.lock");
            if (Files.isSymbolicLink(lockPath)) {
                throw new IOException();
            }
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                throw new SessionException("Session is already in use: " + id);
            }
            long revision = create ? -1 : load(id).revision();
            return new FileLease(id, directory, channel, lock, revision);
        } catch (IOException | RuntimeException failed) {
            // 任一步骤失败都释放已获得的资源，避免阻塞后续恢复。
            try { if (lock != null) lock.release(); } catch (IOException ignored) { }
            try { if (channel != null) channel.close(); } catch (IOException ignored) { }
            if (failed instanceof SessionException error) {
                throw error;
            }
            if (failed instanceof OverlappingFileLockException) {
                throw new SessionException("Session is already in use: " + id);
            }
            throw new SessionException("Failed to open session: " + id);
        }
    }

    /** 仅接受完整 UUID，拒绝会话子目录链接；文件名不来自用户标题。 */
    private Path directory(String id) {
        if (!SessionSnapshot.validId(id)) {
            throw new SessionException("A valid session UUID is required.");
        }
        Path directory = root.resolve(id);
        if (Files.isSymbolicLink(directory)) {
            throw new SessionException("Linked session directories are not supported: " + id);
        }
        return directory;
    }

    /** 一个会话的锁与版本状态，只供持锁的服务使用。 */
    private final class FileLease implements Lease {
        /** 唯一会话 ID 与已经校验的目录。 */
        private final String id;
        private final Path directory;
        /** 锁绑定独立文件的通道，关闭通道同时释放锁。 */
        private final FileChannel channel;
        private final FileLock lock;
        /** 最近成功写入的版本，用于拒绝陈旧覆盖。 */
        private long revision;
        /** 关闭后的 lease 拒绝再次读写。 */
        private boolean closed;

        /** 接收已经获得的锁和初始版本，新建时版本为 -1。 */
        private FileLease(String id, Path directory, FileChannel channel, FileLock lock, long revision) {
            this.id = id;
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
            this.revision = revision;
        }

        /** 持锁后仍读取磁盘最新状态，不用列表缓存恢复。 */
        @Override
        public SessionSnapshot load() {
            checkOpen();
            return JsonSessionStore.this.load(id);
        }

        /** 先验证旧版本，再写临时文件并 force，最后原子替换；失败删除临时文件。 */
        @Override
        public void save(SessionSnapshot snapshot) {
            checkOpen();
            Path temporary = null;
            try {
                Path file = directory.resolve("session.json");
                if (!snapshot.id().equals(id) || snapshot.revision() != revision + 1
                        || (revision < 0 ? Files.exists(file) : JsonSessionStore.this.load(id).revision() != revision)) {
                    throw new SessionException("Session revision conflict: " + id);
                }
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
                if (bytes.length > MAX_BYTES) {
                    throw new SessionException("Session exceeds the 16 MiB snapshot limit: " + id);
                }
                temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                revision = snapshot.revision();
            } catch (IOException failed) {
                throw new SessionException("Failed to atomically save session: " + id);
            } finally {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                }
            }
        }

        /** 防止关闭后或锁失效时继续访问。 */
        private void checkOpen() {
            if (closed || !lock.isValid()) {
                throw new SessionException("Session lock is closed: " + id);
            }
        }

        /** 可重复关闭，操作系统在进程退出时也会释放锁，不删除锁文件。 */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try { channel.close(); } catch (IOException failed) {
                    throw new SessionException("Failed to close session lock: " + id);
                }
            }
        }
    }
}
