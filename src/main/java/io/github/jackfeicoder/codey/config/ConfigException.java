package io.github.jackfeicoder.codey.config;

/** 配置读取或校验失败；由 CLI 捕获并展示字段名或行号，不回显密钥。 */
public final class ConfigException extends RuntimeException {
    /** message 是可展示的错误说明，应包含定位信息而非原始配置值。 */
    public ConfigException(String message) {
        super(message);
    }
}
