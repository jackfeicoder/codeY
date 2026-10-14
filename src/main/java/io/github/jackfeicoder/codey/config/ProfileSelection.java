package io.github.jackfeicoder.codey.config;

/** 模型选择值：Profile 别名与可选模型 ID；字符串格式兼容旧的单别名记录。 */
public record ProfileSelection(String alias, String modelId) {
    /** 别名忽略大小写，模型 ID 保留大小写；不保存地址或密钥。 */
    public ProfileSelection {
        alias = ModelProfiles.normalizeName(alias);
        if (modelId != null) modelId = requireModelId(modelId);
    }

    /** /model 参数、启动覆盖和偏好文件共用解析，最多两个参数。 */
    public static ProfileSelection parse(String selection) {
        if (selection == null || selection.isBlank()) {
            throw new ConfigException("Select a Profile alias and optional model ID.");
        }
        String[] parts = selection.strip().split("\\s+", -1);
        if (parts.length > 2) throw new ConfigException("Use /model ALIAS [MODEL_ID].");
        return new ProfileSelection(parts[0], parts.length == 2 ? parts[1] : null);
    }

    /** 模型 ID 是单个非空标识，允许平台常用的斜杠、冒号和连字符。 */
    static String requireModelId(String id) {
        String value = id.strip();
        if (value.isEmpty() || value.length() > 256 || value.indexOf(',') >= 0
                || value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw new ConfigException("Model IDs must be nonblank single tokens of at most 256 characters.");
        }
        return value;
    }

    /** 单别名用于旧记录；明确模型时将两个字段一起保存，恢复时不依赖列表顺序。 */
    public String value() {
        return modelId == null ? alias : alias + " " + modelId;
    }
}
