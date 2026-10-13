package io.github.jackfeicoder.codey.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 配置加载入口：读取 .env → 合并进程环境 → 选择 Profile → 转换并校验字段。
 * 来源优先级为环境变量高于 .env；Profile 选择可由命令行临时覆盖。
 */
public final class ConfigLoader {
    /** 工具类只提供静态加载方法，无需创建实例。 */
    private ConfigLoader() {
    }

    /** 从启动目录与当前进程环境加载默认选中的模型配置。 */
    public static LlmConfig load() {
        return load(Path.of(""), System.getenv());
    }

    /** 显式传入目录和环境，便于测试或嵌入调用；返回选中配置并立即校验。 */
    public static LlmConfig load(Path workingDirectory, Map<String, String> environment) {
        return loadProfiles(workingDirectory, environment).activeConfig();
    }

    /** 加载所有已声明的 Profile，但暂不校验每套凭证。 */
    public static ModelProfiles loadProfiles() {
        return loadProfiles(Path.of(""), System.getenv());
    }

    /** 使用传入的配置来源，默认选择由 CODEY_ACTIVE_PROFILE 决定。 */
    public static ModelProfiles loadProfiles(Path workingDirectory, Map<String, String> environment) {
        return loadProfiles(workingDirectory, environment, null);
    }

    /**
     * 建立配置快照。字段内容在选中时校验，别名列表在此处校验。
     * @param workingDirectory 启动目录；CODEY_CONFIG_DIR 未指定时在此查找 .env
     * @param environment 进程环境或测试提供的键值，覆盖文件中的同名字段
     * @param profileOverride 命令行指定的别名；null 表示使用默认选择，不修改文件
     */
    public static ModelProfiles loadProfiles(Path workingDirectory, Map<String, String> environment,
                                            String profileOverride) {
        return loadProfiles(workingDirectory, environment, profileOverride, false);
    }

    /** 模型目录不依赖默认别名是否有效；仅列配置状态，不选客户端或改变用户默认。 */
    public static List<ModelProfiles.ProfileInfo> loadModelCatalog(Path workingDirectory, Map<String, String> environment) {
        return loadProfiles(workingDirectory, environment, null, true).listInfo();
    }

    /** 共用配置解析，目录视图临时以首个别名建立元数据快照。 */
    private static ModelProfiles loadProfiles(Path workingDirectory, Map<String, String> environment,
                                             String profileOverride, boolean catalogOnly) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        // values 保存合并后的原始字符串，空环境变量也会覆盖文件值。
        Map<String, String> values = new HashMap<>(DotEnvReader.read(configurationDirectory(workingDirectory, environment).resolve(".env")));
        values.putAll(environment);
        boolean namedProfiles = values.containsKey("CODEY_PROFILE_NAMES"); // 是否采用多 Profile 格式。
        List<String> names = new ArrayList<>(); // 规范化后的别名列表，供后续选择校验使用。
        if (namedProfiles) {
            String declaredNames = required(values, "CODEY_PROFILE_NAMES"); // 文件/环境中逗号分隔的别名。
            for (String candidate : declaredNames.split(",", -1)) {
                // candidate 是单个声明项；保留空项，以便发现末尾逗号等格式错误。
                String name = ModelProfiles.normalizeName(candidate);
                if (names.contains(name)) {
                    throw new ConfigException("CODEY_PROFILE_NAMES contains duplicate aliases (case-insensitive).");
                }
                names.add(name);
            }
        } else {
            // 没有别名列表时，把旧版 CODEY_LLM_* 配置暴露为 default。
            names.add("default");
        }
        String active = profileOverride; // 优先采用命令行选择，其次读取默认别名。
        if (active == null) {
            active = catalogOnly ? names.get(0) : namedProfiles ? required(values, "CODEY_ACTIVE_PROFILE")
                    : values.getOrDefault("CODEY_ACTIVE_PROFILE", "default");
        }
        return new ModelProfiles(names, active, values, namedProfiles);
    }

    /** 显式配置目录与启动工作区分开；未设置时保持从启动目录读取 .env 的原行为。 */
    public static Path configurationDirectory(Path startupDirectory, Map<String, String> environment) {
        String configured = environment.get("CODEY_CONFIG_DIR"); // 由环境或启动脚本提供，不从 .env 自引用。
        if (configured == null) return startupDirectory.toAbsolutePath().normalize();
        if (configured.isBlank()) throw new ConfigException("CODEY_CONFIG_DIR must be a nonblank directory path.");
        try {
            Path directory = startupDirectory.resolve(configured).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(directory)) {
                throw new ConfigException("CODEY_CONFIG_DIR must refer to an existing directory.");
            }
            return directory;
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new ConfigException("CODEY_CONFIG_DIR must be a valid directory path.");
        }
    }

    /** 根据统一字段前缀组装一套配置；不按厂商名字编写专用分支。 */
    static LlmConfig buildConfig(Map<String, String> values, String prefix) {
        String baseUrl = required(values, prefix + "BASE_URL"); // 尚未转换成 URI 的基础地址。
        String model = required(values, prefix + "MODEL"); // 原样用于后续请求的模型 ID。
        // authMode 决定是否要求密钥，未配置时采用 Bearer。
        AuthMode authMode = switch (values.getOrDefault(prefix + "AUTH", "bearer").strip().toLowerCase(Locale.ROOT)) {
            case "bearer" -> AuthMode.BEARER;
            case "none" -> AuthMode.NONE;
            default -> throw new ConfigException(prefix + "AUTH must be bearer or none.");
        };
        String apiKey = authMode == AuthMode.BEARER ? required(values, prefix + "API_KEY") : "";
        URI uri; // 地址的强类型表示，移除末尾斜杠后再交给配置对象校验。
        try {
            uri = new URI(baseUrl.replaceAll("/+$", ""));
        } catch (URISyntaxException invalid) {
            throw new ConfigException(prefix + "BASE_URL must be a valid HTTP(S) base URL.");
        }
        String timeoutKey = prefix + "TIMEOUT_SECONDS"; // 优先选用当前 Profile 的超时字段。
        if (!values.containsKey(timeoutKey) && values.containsKey("CODEY_LLM_TIMEOUT_SECONDS")) {
            timeoutKey = "CODEY_LLM_TIMEOUT_SECONDS";
        }
        String rawTimeout = values.getOrDefault(timeoutKey, "120"); // 缺省超时为 120 秒。
        int timeout; // 将字符串转换为正整数秒数，再创建 Duration。
        try {
            timeout = Integer.parseInt(rawTimeout.strip());
        } catch (NumberFormatException invalid) {
            throw new ConfigException(timeoutKey + " must be a positive integer.");
        }
        if (timeout <= 0) {
            throw new ConfigException(timeoutKey + " must be a positive integer.");
        }
        try {
            return new LlmConfig(uri, apiKey, model, Duration.ofSeconds(timeout), authMode);
        } catch (ConfigException invalid) {
            // 将配置对象的通用字段名换成当前 Profile 的字段名，方便定位问题。
            throw new ConfigException(invalid.getMessage().replace("CODEY_LLM_", prefix));
        }
    }

    /** 读取必填字段，缺失或空白时只报告字段名；返回去除首尾空白后的值。 */
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); // 当前字段的原始配置字符串。
        if (value == null || value.isBlank()) {
            throw new ConfigException(key + " is required. Set it in the process environment or working-directory .env.");
        }
        return value.strip();
    }
}
