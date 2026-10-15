package io.github.jackfeicoder.codey.config;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.tool.builtin.ListFilesTool;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Agent 运行设置：每条用户任务的模型请求预算与目录工具默认深度，不包含模型凭证。 */
public record AgentSettings(int maxIterations, int listFilesDefaultDepth) {
    /** 未配置时使用核心组件的默认值，避免入口与工具默认行为不一致。 */
    public AgentSettings() { this(AgentLoop.DEFAULT_MAX_STEPS, ListFilesTool.DEFAULT_MAX_DEPTH); }

    /** 无论来自文件、环境或直接 Java 调用，都保持同样的边界。 */
    public AgentSettings {
        if (maxIterations < 1 || maxIterations > AgentLoop.MAX_STEPS) {
            throw new ConfigException("CODEY_MAX_ITERATIONS must be an integer within 1..100.");
        }
        if (listFilesDefaultDepth < 1 || listFilesDefaultDepth > ListFilesTool.MAX_DEPTH) {
            throw new ConfigException("CODEY_LIST_FILES_DEFAULT_DEPTH must be an integer within 1..20.");
        }
    }

    /** 与模型配置共用配置目录和环境优先级；运行设置在 CLI 启动时加载。 */
    public static AgentSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        return new AgentSettings(integer(values, "CODEY_MAX_ITERATIONS", AgentLoop.DEFAULT_MAX_STEPS),
                integer(values, "CODEY_LIST_FILES_DEFAULT_DEPTH", ListFilesTool.DEFAULT_MAX_DEPTH));
    }

    /** 未出现的键使用默认值；显式空值、非整数和溢出不静默回退，也不回显原值。 */
    private static int integer(Map<String, String> values, String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw.strip()); }
        catch (NumberFormatException invalid) { throw new ConfigException(key + " must be an integer within its allowed range."); }
    }
}
