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
 * @param summaryMaxTokens 每次摘要输出的配置上限，实际还受模型预留与可用空间约束
 * @param maxCalls 一次压缩允许的摘要请求总次数，与业务 ReAct 轮次分开
 * @param timeoutSeconds 全部分块共享的总超时秒数
 */
public record CompactionSettings(boolean enabled, BigDecimal triggerRatio, BigDecimal targetRatio,
                                 int keepRecentTurns, int summaryMaxTokens, int maxCalls, int timeoutSeconds) {
    public CompactionSettings() { this(true, new BigDecimal("0.8"), new BigDecimal("0.4"), 3, 4096, 8, 60); }

    public CompactionSettings {
        validateRatio(triggerRatio); validateRatio(targetRatio);
        if (targetRatio.compareTo(triggerRatio) >= 0 || keepRecentTurns < 2 || keepRecentTurns > 3
                || summaryMaxTokens < 128 || summaryMaxTokens > 16384 || maxCalls < 1 || maxCalls > 32
                || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new ConfigException("Invalid compaction settings: target < trigger, recent turns 2..3, summary tokens 128..16384, calls 1..32, timeout 1..300 seconds.");
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
                ratio(values, "CODEY_CONTEXT_COMPACTION_TARGET_RATIO", "0.4"), integer(values, "CODEY_CONTEXT_KEEP_RECENT_TURNS", 3),
                integer(values, "CODEY_CONTEXT_SUMMARY_MAX_TOKENS", 4096), integer(values, "CODEY_CONTEXT_COMPACTION_MAX_CALLS", 8),
                integer(values, "CODEY_CONTEXT_COMPACTION_TIMEOUT_SECONDS", 60));
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
