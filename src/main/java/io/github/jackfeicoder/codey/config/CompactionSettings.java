package io.github.jackfeicoder.codey.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 摘要压缩设置；水位针对可用输入预算，独立于预算预警和最大模型窗口。
 * @param enabled 自动与手动摘要的总开关
 * @param triggerRatio 发送前达到此输入预算比例时尝试摘要
 * @param targetRatio 新请求整体的目标比例，不仅是摘要正文
 * @param keepRecentTurns 优先保留的完整已完成用户任务数，当前任务另行完整保留
 * @param summaryMaxTokens 0 表示按剩余空间动态分配；正数为用户额外设置的摘要输出上限
 * @param maxCalls 一次压缩允许的摘要请求总次数，与业务 ReAct 轮次分开
 * @param timeoutSeconds 全部分块共享的总超时秒数
 * @param singleMaxSourceTokens 来源 Token 的 Single 分界，仍需满足当前模型请求预算
 * @param chunkTokens Map 块目标上限，优先沿完整任务边界，模型窗口小时自动缩小
 * @param concurrency 单次压缩最多并行的 Map 数，1..8；Reduce 始终等待全部 Map 完成
 */
public record CompactionSettings(boolean enabled, BigDecimal triggerRatio, BigDecimal targetRatio,
                                 int keepRecentTurns, int summaryMaxTokens, int maxCalls, int timeoutSeconds,
                                 int singleMaxSourceTokens, int chunkTokens, int concurrency) {
    /** 兼容原调用方；新策略默认按 64K 来源分流、64K 分块、最多三个并行请求。 */
    public CompactionSettings(boolean enabled, BigDecimal triggerRatio, BigDecimal targetRatio,
                              int keepRecentTurns, int summaryMaxTokens, int maxCalls, int timeoutSeconds) {
        this(enabled, triggerRatio, targetRatio, keepRecentTurns, summaryMaxTokens, maxCalls, timeoutSeconds, 64000, 64000, 3);
    }
    public CompactionSettings() { this(true, new BigDecimal("0.8"), new BigDecimal("0.2"), 3, 0, 24, 60); }

    public CompactionSettings {
        validateRatio(triggerRatio); validateRatio(targetRatio);
        if (targetRatio.compareTo(triggerRatio) >= 0 || keepRecentTurns < 2 || keepRecentTurns > 3
                || (summaryMaxTokens != 0 && (summaryMaxTokens < 128 || summaryMaxTokens > 1000000)) || maxCalls < 1 || maxCalls > 32
                || timeoutSeconds < 1 || timeoutSeconds > 300 || singleMaxSourceTokens < 512 || singleMaxSourceTokens > 1000000
                || chunkTokens < 512 || chunkTokens > 1000000 || concurrency < 1 || concurrency > 8) {
            throw new ConfigException("Invalid compaction settings: target < trigger, recent turns 2..3, summary tokens 0 (dynamic) or 128..1000000, calls 1..32, timeout 1..300 seconds, source/chunk tokens 512..1000000, concurrency 1..8.");
        }
        triggerRatio = triggerRatio.stripTrailingZeros(); targetRatio = targetRatio.stripTrailingZeros();
    }

    /** 与其他设置共用配置目录及环境优先级；显式空值或非法值报错，不回显原文。 */
    public static CompactionSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        String flag = values.getOrDefault("CODEY_CONTEXT_COMPACTION_ENABLED", "true").strip();
        if (!flag.equalsIgnoreCase("true") && !flag.equalsIgnoreCase("false")) throw invalid("CODEY_CONTEXT_COMPACTION_ENABLED");
        return new CompactionSettings(Boolean.parseBoolean(flag), ratio(values, "CODEY_CONTEXT_COMPACTION_TRIGGER_RATIO", "0.8"),
                ratio(values, "CODEY_CONTEXT_COMPACTION_TARGET_RATIO", "0.2"), integer(values, "CODEY_CONTEXT_KEEP_RECENT_TURNS", 3),
                integer(values, "CODEY_CONTEXT_SUMMARY_MAX_TOKENS", 0), integer(values, "CODEY_CONTEXT_COMPACTION_MAX_CALLS", 24),
                integer(values, "CODEY_CONTEXT_COMPACTION_TIMEOUT_SECONDS", 60),
                integer(values, "CODEY_CONTEXT_COMPACTION_SINGLE_MAX_SOURCE_TOKENS", 64000),
                integer(values, "CODEY_CONTEXT_COMPACTION_CHUNK_TOKENS", 64000), integer(values, "CODEY_CONTEXT_COMPACTION_CONCURRENCY", 3));
    }

    private static BigDecimal ratio(Map<String, String> values, String key, String fallback) {
        String raw = values.getOrDefault(key, fallback).strip();
        if (!raw.matches("(?:0\\.[0-9]{1,6}|1(?:\\.0{1,6})?)")) throw invalid(key);
        return new BigDecimal(raw);
    }
    private static int integer(Map<String, String> values, String key, int fallback) {
        try { return values.containsKey(key) ? Integer.parseInt(values.get(key).strip()) : fallback; }
        catch (NumberFormatException failed) { throw invalid(key); }
    }
    private static void validateRatio(BigDecimal value) {
        if (value == null || value.scale() < 0 || value.scale() > 6 || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0)
            throw new ConfigException("Compaction ratios must be within (0, 1], with at most six decimal places.");
    }
    private static ConfigException invalid(String key) { return new ConfigException("Invalid " + key + "."); }
}
