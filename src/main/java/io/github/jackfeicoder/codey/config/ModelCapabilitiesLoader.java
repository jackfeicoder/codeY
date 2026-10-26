package io.github.jackfeicoder.codey.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 读取共享配置目录 models.json，严格校验全部条目后按 Profile + 模型 ID 精确选择。 */
public final class ModelCapabilitiesLoader {
    /** 配置读取有界，防止异常文件一次占用大量内存；条目数量也设独立上限。 */
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_MODELS = 1024;
    /** 字段白名单使拼写错误明确报错，避免错误容量被静默当成未知。 */
    private static final Set<String> MODEL_FIELDS = Set.of("profile", "modelId", "contextWindow", "outputReserveTokens", "safetyMarginTokens");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ModelCapabilitiesLoader() { }

    /**
     * 与 .env 共用 CODEY_CONFIG_DIR；每次显式加载获得新快照，不做文件监听。
     * Profile 忽略大小写，模型 ID 保持大小写，允许 org/model 等已有合法格式。
     * 文件可提前记录尚未加入 .env 的模型，但不会据此注册模型或赋予 API 访问权限。
     */
    public static ModelCapabilities load(Path startup, Map<String, String> environment,
                                         String profile, String modelId) {
        ProfileSelection selected = new ProfileSelection(profile, modelId);
        if (selected.modelId() == null) throw new ConfigException("A model ID is required for capacity lookup.");
        Path file = ConfigLoader.configurationDirectory(startup, environment).resolve("models.json");
        try {
            byte[] bytes;
            // 仅“不存在”表示可选配置缺失；目录、链接、不可读等情况明确报错。
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                bytes = input.readNBytes(MAX_BYTES + 1);
            } catch (java.nio.file.NoSuchFileException missing) {
                if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return ModelCapabilities.unknown();
                throw missing;
            }
            if (bytes.length > MAX_BYTES) throw invalid();
            JsonNode root = MAPPER.readTree(bytes);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("models").isArray()
                    || root.path("models").size() > MAX_MODELS) throw invalid();

            Map<ProfileSelection, ModelCapabilities> entries = new HashMap<>();
            for (JsonNode item : root.path("models")) {
                if (!item.isObject() || item.size() < 3 || item.size() > MODEL_FIELDS.size()) throw invalid();
                var fields = item.fieldNames();
                while (fields.hasNext()) if (!MODEL_FIELDS.contains(fields.next())) throw invalid();
                if (!item.path("profile").isTextual() || !item.path("modelId").isTextual()
                        || !item.path("contextWindow").isIntegralNumber()
                        || !item.path("contextWindow").canConvertToLong()) throw invalid();
                ProfileSelection key = new ProfileSelection(item.path("profile").textValue(), item.path("modelId").textValue());
                var capacity = new ModelCapabilities(item.path("contextWindow").longValue(),
                        optionalInteger(item, "outputReserveTokens"), optionalInteger(item, "safetyMarginTokens"));
                if (entries.putIfAbsent(key, capacity) != null) throw invalid();
            }
            return entries.getOrDefault(selected, ModelCapabilities.unknown());
        } catch (IOException | RuntimeException invalid) {
            // 不传播 Jackson/文件系统原始异常，防止误填到文件里的凭证出现在错误输出中。
            throw invalid();
        }
    }

    /** 可选字段省略时继承；显式 null、小数和溢出不是有效覆盖值。 */
    private static Long optionalInteger(JsonNode item, String field) {
        if (!item.has(field)) return null;
        if (!item.get(field).isIntegralNumber() || !item.get(field).canConvertToLong()) throw invalid();
        return item.get(field).longValue();
    }

    private static ConfigException invalid() {
        return new ConfigException("Invalid or unreadable models.json; expected {\"models\":[{\"profile\":\"alias\","
                + "\"modelId\":\"id\",\"contextWindow\":128000}]} with unique profile/modelId pairs, positive windows and optional integer outputReserveTokens/safetyMarginTokens.");
    }
}
