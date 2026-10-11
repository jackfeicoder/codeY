package io.github.jackfeicoder.codey.config;

import java.net.URI;
import java.time.Duration;

/** Immutable, validated LLM settings. Construction goes through ConfigLoader. */
public record LlmConfig(URI baseUrl, String apiKey, String model, Duration timeout, AuthMode authMode) {
    /** Keeps the original single-model construction compatible. */
    public LlmConfig(URI baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, AuthMode.BEARER);
    }

    public LlmConfig {
        if (baseUrl == null || baseUrl.getHost() == null
                || !("https".equalsIgnoreCase(baseUrl.getScheme())
                || "http".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new ConfigException("CODEY_LLM_BASE_URL must be an HTTP(S) base URL without credentials, query or fragment.");
        }
        if (authMode == null) {
            throw new ConfigException("CODEY_LLM_AUTH must be bearer or none.");
        }
        if (authMode == AuthMode.BEARER
                && (apiKey == null || apiKey.isBlank() || "YOUR_API_KEY".equals(apiKey))) {
            throw new ConfigException("CODEY_LLM_API_KEY is required; replace the example placeholder.");
        }
        if (authMode == AuthMode.NONE) {
            apiKey = "";
        }
        if (model == null || model.isBlank() || "YOUR_MODEL".equals(model)) {
            throw new ConfigException("CODEY_LLM_MODEL is required; replace the example placeholder.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigException("CODEY_LLM_TIMEOUT_SECONDS must be a positive integer.");
        }
    }

    /** Override the record default to prevent accidental credential logging. */
    @Override
    public String toString() {
        return "LlmConfig[baseUrl=" + baseUrl + ", apiKey=<redacted>, model="
                + model + ", timeout=" + timeout + ", authMode=" + authMode + "]";
    }
}
