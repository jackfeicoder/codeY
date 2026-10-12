package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 会话配置，不包含模型凭证；工作区只是新建时的建议选项。 */
public record SessionSettings(Path dataDirectory, String suggestedWorkspace, boolean generateTitles) {
    /** 从启动目录 dotenv 与进程环境加载，环境变量优先；不读取工作区里的配置。 */
    public static SessionSettings load(Path startupDirectory, Map<String, String> environment) {
        Map<String, String> values = new HashMap<>(DotEnvReader.read(startupDirectory.resolve(".env")));
        values.putAll(environment);
        String dataPath = values.getOrDefault("CODEY_DATA_DIR",
                Path.of(System.getProperty("user.home"), ".codey").toString());
        String titleMode = values.getOrDefault("CODEY_AUTO_TITLE", "true").strip();
        if (dataPath.isBlank() || !("true".equalsIgnoreCase(titleMode) || "false".equalsIgnoreCase(titleMode))) {
            throw new ConfigException("CODEY_DATA_DIR must be nonblank; CODEY_AUTO_TITLE must be true or false.");
        }
        try {
            return new SessionSettings(startupDirectory.resolve(dataPath).toAbsolutePath().normalize(),
                    values.get("CODEY_WORKSPACE"), Boolean.parseBoolean(titleMode));
        } catch (IllegalArgumentException invalid) {
            throw new ConfigException("CODEY_DATA_DIR must be a valid path.");
        }
    }
}
