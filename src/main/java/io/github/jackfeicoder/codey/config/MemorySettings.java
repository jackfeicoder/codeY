package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.*;

/** 长期记忆的独立输入额度，最终还要服从模型总输入预算。 */
public record MemorySettings(int alwaysTokens, int recallTokens, int maxResults) {
    public MemorySettings() { this(1000, 2000, 5); }
    public MemorySettings {
        if (alwaysTokens < 128 || alwaysTokens > 16000 || recallTokens < 0 || recallTokens > 32000 || maxResults < 1 || maxResults > 20)
            throw new ConfigException("Memory limits: always 128..16000, recall 0..32000 tokens, results 1..20.");
    }
    /** 环境覆盖共享配置目录 .env；不访问供应商接口。 */
    public static MemorySettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        try { return new MemorySettings(integer(values, "CODEY_MEMORY_ALWAYS_TOKENS", 1000),
                integer(values, "CODEY_MEMORY_RECALL_TOKENS", 2000), integer(values, "CODEY_MEMORY_MAX_RESULTS", 5)); }
        catch (NumberFormatException invalid) { throw new ConfigException("CODEY_MEMORY_* limits must be integers."); }
    }
    private static int integer(Map<String, String> values, String key, int fallback) {
        return Integer.parseInt(values.getOrDefault(key, "" + fallback).strip());
    }
}
