package io.github.jackfeicoder.codey.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** 用户级模型偏好：只保存最后选择的 Profile 别名，不保存密钥或改变 .env。 */
public final class ModelPreferenceStore {
    /** 微小偏好文件的读取上限，拒绝异常内容。 */
    private static final int MAX_BYTES = 4096;
    /** 位于 CODEY_DATA_DIR 的单独文件，独立于各会话。 */
    private final Path file;
    /** 拒绝重复字段和尾随数据，不回显原始文件正文。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 构造时不读取或创建目录，浏览会话不依赖模型偏好。 */
    public ModelPreferenceStore(Path dataDirectory) {
        file = dataDirectory.toAbsolutePath().normalize().resolve("model-preference.json");
    }

    /** 首次没有记录时返回 empty，调用方使用保存的会话别名或 .env 默认值。 */
    public Optional<String> load() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            byte[] bytes;
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new IOException();
            var body = MAPPER.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 2
                    || !body.path("schemaVersion").isIntegralNumber() || !body.path("schemaVersion").canConvertToInt()
                    || body.path("schemaVersion").asInt() != 1
                    || !body.path("lastUsedProfile").isTextual()) throw new IOException();
            return Optional.of(ModelProfiles.normalizeName(body.path("lastUsedProfile").asText()));
        } catch (IOException | RuntimeException invalid) {
            throw new ConfigException("Failed to read model-preference.json; default preference was ignored.");
        }
    }

    /** 原子替换偏好；多进程选择时最后一次成功写入生效，不修改会话文件。 */
    public void save(String profile) {
        String alias = ModelProfiles.normalizeName(profile);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException();
            }
            byte[] bytes = MAPPER.writeValueAsBytes(MAPPER.createObjectNode()
                    .put("schemaVersion", 1).put("lastUsedProfile", alias));
            temporary = Files.createTempFile(file.getParent(), "model-preference-", ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failed) {
            throw new ConfigException("Failed to save model-preference.json; the default may not survive restart.");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
