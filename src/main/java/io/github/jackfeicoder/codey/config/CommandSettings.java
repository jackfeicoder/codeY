package io.github.jackfeicoder.codey.config;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 本地进程设置：宿主控制时间、输出及编码，不接受模型放大硬上限。 */
public record CommandSettings(int timeoutSeconds, int maxTimeoutSeconds, int maxOutputChars, Charset charset) {
    /** 输出预算是 stdout + stderr 的保留字符预算，两个流各占一半。 */
    public CommandSettings() { this(60, 300, 8000, Charset.defaultCharset()); }
    public CommandSettings {
        if (maxTimeoutSeconds < 1 || maxTimeoutSeconds > 600 || timeoutSeconds < 1 || timeoutSeconds > maxTimeoutSeconds)
            throw new ConfigException("Command timeout must satisfy 1 <= default <= maximum <= 600 seconds.");
        if (maxOutputChars < 256 || maxOutputChars > 32000)
            throw new ConfigException("CODEY_COMMAND_MAX_OUTPUT_CHARS must be within 256..32000.");
        java.util.Objects.requireNonNull(charset);
    }
    /** .env 与模型配置共用查找目录，环境变量优先；不读取或输出模型密钥。 */
    public static CommandSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        Charset charset;
        try { charset = Charset.forName(values.getOrDefault("CODEY_COMMAND_CHARSET", Charset.defaultCharset().name()).strip()); }
        catch (IllegalArgumentException invalid) { throw new ConfigException("CODEY_COMMAND_CHARSET must name a supported charset."); }
        return new CommandSettings(integer(values, "CODEY_COMMAND_TIMEOUT_SECONDS", 60),
                integer(values, "CODEY_COMMAND_MAX_TIMEOUT_SECONDS", 300),
                integer(values, "CODEY_COMMAND_MAX_OUTPUT_CHARS", 8000), charset);
    }
    private static int integer(Map<String, String> values, String key, int fallback) {
        try { return values.containsKey(key) ? Integer.parseInt(values.get(key).strip()) : fallback; }
        catch (NumberFormatException invalid) { throw new ConfigException(key + " must be an integer."); }
    }
}
