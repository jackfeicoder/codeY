package io.github.jackfeicoder.codey.config;

import io.github.jackfeicoder.codey.policy.PermissionMode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 宿主审批配置；管道审批需用户显式启用，避免消费后续普通聊天作为批准。 */
public record PermissionSettings(PermissionMode mode, boolean pipedApprovals) {
    public PermissionSettings() { this(PermissionMode.ASK, false); }
    public PermissionSettings { java.util.Objects.requireNonNull(mode); }
    public static PermissionSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        PermissionMode mode;
        try { mode = PermissionMode.parse(values.getOrDefault("CODEY_PERMISSION_MODE", "ask")); }
        catch (IllegalArgumentException invalid) { throw new ConfigException("CODEY_PERMISSION_MODE must be ask, auto or full."); }
        String piped = values.getOrDefault("CODEY_ALLOW_PIPED_APPROVALS", "false").strip();
        if (!piped.equalsIgnoreCase("true") && !piped.equalsIgnoreCase("false")) {
            throw new ConfigException("CODEY_ALLOW_PIPED_APPROVALS must be true or false.");
        }
        return new PermissionSettings(mode, Boolean.parseBoolean(piped));
    }
}
