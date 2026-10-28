package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.*;

/** 自动记忆独立调用额度；失败不影响已保存的用户任务，不自动重试。 */
public record MemoryExtractionSettings(boolean enabled, int timeoutSeconds, int maxInputTokens, int maxOutputTokens) {
    public MemoryExtractionSettings() { this(true, 10, 4096, 1024); }
    public MemoryExtractionSettings {
        if (timeoutSeconds < 1 || timeoutSeconds > 60 || maxInputTokens < 512 || maxInputTokens > 16000
                || maxOutputTokens < 128 || maxOutputTokens > 4096) throw new ConfigException("Invalid automatic memory extraction limits.");
    }
    public static MemoryExtractionSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        String enabled = values.getOrDefault("CODEY_MEMORY_AUTO_ENABLED", "true").strip();
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) throw new ConfigException("CODEY_MEMORY_AUTO_ENABLED must be true or false.");
        try { return new MemoryExtractionSettings(Boolean.parseBoolean(enabled),
                Integer.parseInt(values.getOrDefault("CODEY_MEMORY_AUTO_TIMEOUT_SECONDS", "10").strip()),
                Integer.parseInt(values.getOrDefault("CODEY_MEMORY_AUTO_MAX_INPUT_TOKENS", "4096").strip()),
                Integer.parseInt(values.getOrDefault("CODEY_MEMORY_AUTO_MAX_OUTPUT_TOKENS", "1024").strip())); }
        catch (NumberFormatException invalid) { throw new ConfigException("CODEY_MEMORY_AUTO_* limits must be integers."); }
    }
}
