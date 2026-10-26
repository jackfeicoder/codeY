package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 流式传输兼容开关：聊天默认 SSE；标题始终通过非流式 generate 请求。 */
public record StreamingSettings(boolean enabled, boolean includeUsage) {
    public StreamingSettings() { this(true, true); }

    /** 共用现有配置目录和环境优先级，修改后重启；不按供应商硬编码。 */
    public static StreamingSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        return new StreamingSettings(flag(values, "CODEY_LLM_STREAM"), flag(values, "CODEY_LLM_STREAM_INCLUDE_USAGE"));
    }
    private static boolean flag(Map<String, String> values, String key) {
        String value = values.getOrDefault(key, "true").strip();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new ConfigException(key + " must be true or false.");
        return Boolean.parseBoolean(value);
    }
}
