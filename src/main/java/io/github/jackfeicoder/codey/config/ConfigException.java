package io.github.jackfeicoder.codey.config;

/** Configuration errors contain field names and locations, never raw values. */
public final class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
