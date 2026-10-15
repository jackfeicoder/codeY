package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证运行参数的默认值、共享配置目录、环境优先级和非法值处理，不读取真实配置。 */
class AgentSettingsTest {
    /** 每项测试独立的临时配置目录。 */
    @TempDir Path directory;

    /** 缺少配置文件与缺少字段都采用相同默认值。 */
    @Test
    void defaultsAreTwentyRequestsAndTenLevels() throws Exception {
        assertEquals(new AgentSettings(20, 10), AgentSettings.load(directory, Map.of()));
        Files.writeString(directory.resolve(".env"), "UNRELATED=value\n");
        assertEquals(new AgentSettings(), AgentSettings.load(directory, Map.of()));
    }

    /** 全局启动器的配置目录与工作区独立，环境变量覆盖该目录的 .env。 */
    @Test
    void loadsSharedDirectoryAndEnvironmentOverridesFile() throws Exception {
        Path config = Files.createDirectory(directory.resolve("config"));
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        Files.writeString(config.resolve(".env"), "CODEY_MAX_ITERATIONS=7\nCODEY_LIST_FILES_DEFAULT_DEPTH=4\n");
        assertEquals(new AgentSettings(7, 4), AgentSettings.load(workspace,
                Map.of("CODEY_CONFIG_DIR", config.toString())));
        assertEquals(new AgentSettings(9, 6), AgentSettings.load(workspace,
                Map.of("CODEY_CONFIG_DIR", config.toString(), "CODEY_MAX_ITERATIONS", " 9 ",
                        "CODEY_LIST_FILES_DEFAULT_DEPTH", "6")));
    }

    /** 轮次的空值、溢出、非整数及越界值在启动时报告，不回显原始配置内容。 */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "-1", "101", "1.5", "2147483648", "secret-fixture"})
    void rejectsInvalidIterationValues(String value) {
        var error = assertThrows(ConfigException.class, () -> AgentSettings.load(directory,
                Map.of("CODEY_MAX_ITERATIONS", value)));
        assertTrue(error.getMessage().contains("CODEY_MAX_ITERATIONS"));
        assertFalse(error.getMessage().contains("secret-fixture"));
    }

    /** 目录工具 schema 和执行规则共用同一深度范围。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "0", "21", "1.5", "2147483648"})
    void rejectsInvalidDepthValues(String value) {
        assertThrows(ConfigException.class, () -> AgentSettings.load(directory,
                Map.of("CODEY_LIST_FILES_DEFAULT_DEPTH", value)));
    }

    /** 支持边界值，直接构造也受相同限制。 */
    @Test
    void validatesDirectConstructionAndAcceptsBoundaries() {
        assertEquals(new AgentSettings(1, 1), AgentSettings.load(directory,
                Map.of("CODEY_MAX_ITERATIONS", "1", "CODEY_LIST_FILES_DEFAULT_DEPTH", "1")));
        assertEquals(new AgentSettings(100, 20), AgentSettings.load(directory,
                Map.of("CODEY_MAX_ITERATIONS", "100", "CODEY_LIST_FILES_DEFAULT_DEPTH", "20")));
        assertThrows(ConfigException.class, () -> new AgentSettings(0, 10));
        assertThrows(ConfigException.class, () -> new AgentSettings(20, 21));
    }
}
