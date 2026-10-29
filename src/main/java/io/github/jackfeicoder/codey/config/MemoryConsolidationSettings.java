package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.*;

/** 小规模记忆整理水位；不是会话压缩，不按模型大窗口的比例积累记忆。 */
public record MemoryConsolidationSettings(boolean enabled, int entryThreshold, int tokenThreshold,
        int cooldownSeconds, int minGrowth, int timeoutSeconds, int maxInputTokens, int maxOutputTokens) {
    public MemoryConsolidationSettings() { this(true, 50, 8000, 3600, 10, 20, 16000, 4096); }
    public MemoryConsolidationSettings {
        if (entryThreshold < 2 || entryThreshold > 1000 || tokenThreshold < 128 || tokenThreshold > 1000000
                || cooldownSeconds < 0 || cooldownSeconds > 86400 || minGrowth < 1 || minGrowth > 1000
                || timeoutSeconds < 1 || timeoutSeconds > 60 || maxInputTokens < 512 || maxInputTokens > 1000000
                || maxOutputTokens < 128 || maxOutputTokens > 16000) throw new ConfigException("Invalid memory consolidation limits.");
    }
    public static MemoryConsolidationSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env"))); values.putAll(environment);
        String enabled = values.getOrDefault("CODEY_MEMORY_TIDY_ENABLED", "true").strip();
        if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) throw new ConfigException("CODEY_MEMORY_TIDY_ENABLED must be true or false.");
        try { return new MemoryConsolidationSettings(Boolean.parseBoolean(enabled), number(values,"ENTRIES",50), number(values,"TOKENS",8000),
                number(values,"COOLDOWN_SECONDS",3600), number(values,"MIN_GROWTH",10), number(values,"TIMEOUT_SECONDS",20),
                number(values,"MAX_INPUT_TOKENS",16000), number(values,"MAX_OUTPUT_TOKENS",4096)); }
        catch (NumberFormatException invalid) { throw new ConfigException("CODEY_MEMORY_TIDY_* limits must be integers."); }
    }
    private static int number(Map<String,String> values, String name, int fallback) {
        return Integer.parseInt(values.getOrDefault("CODEY_MEMORY_TIDY_"+name, ""+fallback).strip());
    }
}
