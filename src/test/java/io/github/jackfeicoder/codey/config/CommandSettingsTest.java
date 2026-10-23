package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 进程设置沿用统一配置位置及优先级，错误不回显用户配置内容。 */
class CommandSettingsTest {
    @TempDir Path directory;
    @Test void defaultsAndEnvironmentPriorityUseExistingConfigurationDirectory() throws Exception {
        assertEquals(new CommandSettings(), CommandSettings.load(directory, Map.of()));
        Files.writeString(directory.resolve(".env"), "CODEY_COMMAND_TIMEOUT_SECONDS=2\nCODEY_COMMAND_MAX_TIMEOUT_SECONDS=5\nCODEY_COMMAND_MAX_OUTPUT_CHARS=512\nCODEY_COMMAND_CHARSET=UTF-8\n");
        assertEquals(new CommandSettings(2, 5, 512, StandardCharsets.UTF_8), CommandSettings.load(directory, Map.of()));
        assertEquals(4, CommandSettings.load(directory, Map.of("CODEY_COMMAND_TIMEOUT_SECONDS", "4")).timeoutSeconds());
        Path other = Files.createDirectory(directory.resolve("config"));
        Files.writeString(other.resolve(".env"), "CODEY_COMMAND_TIMEOUT_SECONDS=3\n");
        assertEquals(3, CommandSettings.load(directory, Map.of("CODEY_CONFIG_DIR", other.toString())).timeoutSeconds());
    }
    @Test void rejectsUnboundedTimeoutsAndInconsistentDefaults() {
        assertThrows(ConfigException.class, () -> new CommandSettings(0, 300, 8000, StandardCharsets.UTF_8));
        assertThrows(ConfigException.class, () -> new CommandSettings(60, 30, 8000, StandardCharsets.UTF_8));
        assertThrows(ConfigException.class, () -> new CommandSettings(60, 601, 8000, StandardCharsets.UTF_8));
    }
    @Test void rejectsOutputAndEncodingErrorsWithoutLeakingRawValues() {
        for (String key : java.util.List.of("CODEY_COMMAND_TIMEOUT_SECONDS", "CODEY_COMMAND_MAX_OUTPUT_CHARS", "CODEY_COMMAND_CHARSET")) {
            var error = assertThrows(ConfigException.class, () -> CommandSettings.load(directory, Map.of(key, "secret-fixture-invalid")));
            assertFalse(error.getMessage().contains("secret-fixture-invalid"));
        }
        assertThrows(ConfigException.class, () -> new CommandSettings(60, 300, 100, StandardCharsets.UTF_8));
        assertThrows(ConfigException.class, () -> new CommandSettings(60, 300, 32001, StandardCharsets.UTF_8));
    }
}
