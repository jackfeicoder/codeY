package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 本地独立读取的调度设置，与模型轮次、每轮工具数量及命令超时分别管理。 */
public record ToolSchedulingSettings(int maxParallel, int readTimeoutSeconds) {
    public static final int MAX_PARALLEL = 8;
    public ToolSchedulingSettings() { this(4,30); }
    public ToolSchedulingSettings {
        if (maxParallel < 1 || maxParallel > MAX_PARALLEL)
            throw new ConfigException("CODEY_TOOL_MAX_PARALLEL must be an integer within 1..8.");
        if (readTimeoutSeconds < 1 || readTimeoutSeconds > 120)
            throw new ConfigException("CODEY_TOOL_READ_TIMEOUT_SECONDS must be an integer within 1..120.");
    }
    /** 共用配置目录；系统环境覆盖 .env，启动时固定本次运行配置。 */
    public static ToolSchedulingSettings load(Path startup,Map<String,String> environment) {
        var values=new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup,environment).resolve(".env")));
        values.putAll(environment);
        return new ToolSchedulingSettings(integer(values,"CODEY_TOOL_MAX_PARALLEL",4),integer(values,"CODEY_TOOL_READ_TIMEOUT_SECONDS",30));
    }
    private static int integer(Map<String,String> values,String name,int fallback) {
        try { return values.containsKey(name)?Integer.parseInt(values.get(name).strip()):fallback; }
        catch(NumberFormatException invalid){throw new ConfigException(name+" must be an integer within its allowed range.");}
    }
}
