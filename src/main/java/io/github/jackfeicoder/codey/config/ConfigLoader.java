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

/** Precedence: process environment > working-directory .env > defaults. */
public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static LlmConfig load() {
        return load(Path.of(""), System.getenv());
    }

    /** Explicit inputs make precedence testable without changing the process environment. */
    public static LlmConfig load(Path workingDirectory, Map<String, String> environment) {
        return loadProfiles(workingDirectory, environment).activeConfig();
    }

    public static ModelProfiles loadProfiles() {
        return loadProfiles(Path.of(""), System.getenv());
    }

    public static ModelProfiles loadProfiles(Path workingDirectory, Map<String, String> environment) {
        return loadProfiles(workingDirectory, environment, null);
    }

    /** profileOverride takes precedence over CODEY_ACTIVE_PROFILE; it does not rewrite the file. */
    public static ModelProfiles loadProfiles(Path workingDirectory, Map<String, String> environment,
                                            String profileOverride) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        Map<String, String> values = new HashMap<>(DotEnvReader.read(workingDirectory.resolve(".env")));
        values.putAll(environment);
        boolean namedProfiles = values.containsKey("CODEY_PROFILE_NAMES");
        List<String> names = new ArrayList<>();
        if (namedProfiles) {
            String declaredNames = required(values, "CODEY_PROFILE_NAMES");
            for (String candidate : declaredNames.split(",", -1)) {
                String name = ModelProfiles.normalizeName(candidate);
                if (names.contains(name)) {
                    throw new ConfigException("CODEY_PROFILE_NAMES contains duplicate aliases (case-insensitive).");
                }
                names.add(name);
            }
        } else {
            names.add("default");
        }
        String active = profileOverride;
        if (active == null) {
            active = namedProfiles ? required(values, "CODEY_ACTIVE_PROFILE")
                    : values.getOrDefault("CODEY_ACTIVE_PROFILE", "default");
        }
        return new ModelProfiles(names, active, values, namedProfiles);
    }

    static LlmConfig buildConfig(Map<String, String> values, String prefix) {
        String baseUrl = required(values, prefix + "BASE_URL");
        String model = required(values, prefix + "MODEL");
        AuthMode authMode = switch (values.getOrDefault(prefix + "AUTH", "bearer").strip().toLowerCase(Locale.ROOT)) {
            case "bearer" -> AuthMode.BEARER;
            case "none" -> AuthMode.NONE;
            default -> throw new ConfigException(prefix + "AUTH must be bearer or none.");
        };
        String apiKey = authMode == AuthMode.BEARER ? required(values, prefix + "API_KEY") : "";
        URI uri;
        try {
            uri = new URI(baseUrl.replaceAll("/+$", ""));
        } catch (URISyntaxException invalid) {
            throw new ConfigException(prefix + "BASE_URL must be a valid HTTP(S) base URL.");
        }
        String timeoutKey = prefix + "TIMEOUT_SECONDS";
        if (!values.containsKey(timeoutKey) && values.containsKey("CODEY_LLM_TIMEOUT_SECONDS")) {
            timeoutKey = "CODEY_LLM_TIMEOUT_SECONDS";
        }
        String rawTimeout = values.getOrDefault(timeoutKey, "120");
        int timeout;
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
            throw new ConfigException(invalid.getMessage().replace("CODEY_LLM_", prefix));
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new ConfigException(key + " is required. Set it in the process environment or working-directory .env.");
        }
        return value.strip();
    }
}
