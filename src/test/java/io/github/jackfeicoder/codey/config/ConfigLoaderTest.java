package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 配置加载测试：用临时文件和显式环境 Map 验证覆盖顺序、默认值、字段校验与脱敏。 */
class ConfigLoaderTest {
    /** JUnit 为本次测试创建的临时目录，与真实项目配置隔离。 */
    @TempDir
    Path directory;

    /** 脚本启动时配置目录固定，但调用者的工作目录和启动目录配置不会被改动。 */
    @Test
    void explicitConfigurationDirectoryLoadsSharedEnvAndPreservesWorkingDirectory() throws Exception {
        Path config = Files.createDirectory(directory.resolve("shared config"));
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Files.writeString(config.resolve(".env"), "CODEY_LLM_BASE_URL=http://127.0.0.1:8000/v1\n"
                + "CODEY_LLM_AUTH=none\nCODEY_LLM_MODEL=shared-model\n");
        Files.writeString(workspace.resolve(".env"), "CODEY_LLM_MODEL=workspace-model\n");
        assertEquals("shared-model", ConfigLoader.load(workspace, Map.of("CODEY_CONFIG_DIR", config.toString())).model());
        assertEquals("override-model", ConfigLoader.load(workspace, Map.of("CODEY_CONFIG_DIR", "../shared config",
                "CODEY_LLM_MODEL", "override-model")).model());
        assertEquals("CODEY_LLM_MODEL=workspace-model\n", Files.readString(workspace.resolve(".env")));
    }

    /** 显式配置路径无效时展示字段名，不回显路径文本或继续读取错误目录。 */
    @Test
    void invalidExplicitConfigurationDirectoryIsRejected() {
        for (String value : new String[]{"", "missing", "invalid\0path"}) {
            assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, Map.of("CODEY_CONFIG_DIR", value)));
        }
    }

    /** 验证文件字段能够加载，并使用缺省超时和规范化基础地址。 */
    @Test
    void loadsFileAndUsesDefaultTimeout() throws IOException {
        writeEnv("""
                CODEY_LLM_BASE_URL=https://example.com/v1/
                CODEY_LLM_API_KEY=fixture-api-key
                CODEY_LLM_MODEL=fixture-model
                """);
        // 本测试加载或构造的模型配置，用于检查字段与认证。
        LlmConfig config = ConfigLoader.load(directory, Map.of());
        assertEquals(URI.create("https://example.com/v1"), config.baseUrl());
        assertEquals("fixture-api-key", config.apiKey());
        assertEquals("fixture-model", config.model());
        assertEquals(Duration.ofSeconds(120), config.timeout());
    }

    /** 验证环境变量覆盖文件同名字段，且输入 Map 不被修改。 */
    @Test
    void environmentOverridesFileWithoutChangingCallerMap() throws IOException {
        writeEnv("""
                CODEY_LLM_BASE_URL=https://example.com/v1
                CODEY_LLM_API_KEY=file-key
                CODEY_LLM_MODEL=file-model
                CODEY_LLM_TIMEOUT_SECONDS=30
                """);
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = Map.of("CODEY_LLM_API_KEY", "environment-key",
                "CODEY_LLM_TIMEOUT_SECONDS", "45");
        // 本测试加载或构造的模型配置，用于检查字段与认证。
        LlmConfig config = ConfigLoader.load(directory, environment);
        assertEquals("environment-key", config.apiKey());
        assertEquals("file-model", config.model());
        assertEquals(Duration.ofSeconds(45), config.timeout());
        assertEquals(2, environment.size());
    }

    /** 验证空环境值仍有优先级，必填项失败时不会偷偷使用文件值。 */
    @Test
    void blankEnvironmentValueDoesNotFallBackToFile() throws IOException {
        writeEnv("CODEY_LLM_API_KEY=file-key\n");
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_API_KEY", " ");
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_API_KEY"));
        assertFalse(error.getMessage().contains("file-key"));
    }

    /** 验证环境配置齐全时允许没有 .env 文件。 */
    @Test
    void fileIsOptionalWhenEnvironmentIsComplete() {
        assertEquals("fixture-model", ConfigLoader.load(directory, validEnvironment()).model());
    }

    /** 依次移除必填字段，验证报错定位字段而不包含密钥。 */
    @ParameterizedTest
    @ValueSource(strings = {"CODEY_LLM_BASE_URL", "CODEY_LLM_API_KEY", "CODEY_LLM_MODEL"})
    void missingRequiredFieldsIdentifyOnlyTheField(String field) {
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.remove(field);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains(field));
        assertFalse(error.getMessage().contains("fixture-api-key"));
    }

    /** 验证非正整数、空值及溢出的超时配置被拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "", "99999999999999999999"})
    void rejectsInvalidTimeout(String timeout) {
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_TIMEOUT_SECONDS", timeout);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_TIMEOUT_SECONDS"));
    }

    /** 验证非法地址或携带凭证的地址被拒绝，异常不回显配置值。 */
    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://example.com/v1", "https:///v1",
            "https://user:fixture-api-key@example.com/v1",
            "https://example.com/v1?key=fixture-api-key", "https://example.com/v1#fragment"})
    void rejectsInvalidOrCredentialBearingBaseUrlsWithoutEchoingValues(String url) {
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_BASE_URL", url);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_BASE_URL"));
        assertFalse(error.getMessage().contains("fixture-api-key"));
    }

    /** 验证本地服务可使用 HTTP 基础地址。 */
    @Test
    void localhostHttpIsAllowedForLocalCompatibleServers() {
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_BASE_URL", "http://localhost:11434/v1");
        assertEquals("localhost", ConfigLoader.load(directory, environment).baseUrl().getHost());
    }

    /** 验证示例密钥和模型占位符需要替换。 */
    @Test
    void examplePlaceholdersAreRejected() {
        // 模拟进程环境的键值，不修改真实系统环境。
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_API_KEY", "YOUR_API_KEY");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, environment));
        environment.put("CODEY_LLM_API_KEY", "fixture-api-key");
        environment.put("CODEY_LLM_MODEL", "YOUR_MODEL");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, environment));
    }

    /** 验证打印配置对象时密钥已脱敏。 */
    @Test
    void toStringRedactsApiKey() {
        // 本测试加载或构造的模型配置，用于检查字段与认证。
        LlmConfig config = ConfigLoader.load(directory, validEnvironment());
        assertTrue(config.toString().contains("apiKey=<redacted>"));
        assertFalse(config.toString().contains("fixture-api-key"));
    }

    /** 验证直接创建配置 record 也会检查必填约束。 */
    @Test
    void directRecordConstructionAlsoValidates() {
        assertThrows(ConfigException.class, () -> new LlmConfig(URI.create("https://example.com"),
                "", "fixture-model", Duration.ofSeconds(120)));
    }

    /** 验证存在但不可读取的 .env 不会被当成缺失文件忽略。 */
    @Test
    void unreadableFileDoesNotBehaveLikeMissingFile() throws IOException {
        Files.createDirectory(directory.resolve(".env"));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, validEnvironment()));
    }

    /** 将测试配置写入 JUnit 提供的临时目录，不接触真实 .env。 */
    private void writeEnv(String text) throws IOException {
        Files.writeString(directory.resolve(".env"), text, StandardCharsets.UTF_8);
    }

    /** 创建一套有效的假环境配置，供其他测试修改个别字段。 */
    private static Map<String, String> validEnvironment() {
        return new HashMap<>(Map.of("CODEY_LLM_BASE_URL", "https://example.com/v1",
                "CODEY_LLM_API_KEY", "fixture-api-key", "CODEY_LLM_MODEL", "fixture-model"));
    }
}
