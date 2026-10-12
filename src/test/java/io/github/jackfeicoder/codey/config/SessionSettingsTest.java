package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 会话配置沿用启动目录 dotenv 与环境优先级，不需要模型密钥。 */
class SessionSettingsTest {
    @TempDir Path directory;

    @Test
    void environmentOverridesFileAndRelativeStorageUsesStartup() throws Exception {
        Files.writeString(directory.resolve(".env"), "CODEY_AUTO_TITLE=true\nCODEY_DATA_DIR=from-file\nCODEY_WORKSPACE=project\n");
        SessionSettings settings = SessionSettings.load(directory,
                Map.of("CODEY_AUTO_TITLE", "false", "CODEY_DATA_DIR", "from-env"));
        assertFalse(settings.generateTitles());
        assertEquals(directory.resolve("from-env").toAbsolutePath(), settings.dataDirectory());
        assertEquals("project", settings.suggestedWorkspace());
    }

    @Test
    void rejectsBlankStorageAndInvalidBoolean() {
        assertThrows(ConfigException.class, () -> SessionSettings.load(directory, Map.of("CODEY_AUTO_TITLE", "maybe")));
        assertThrows(ConfigException.class, () -> SessionSettings.load(directory, Map.of("CODEY_DATA_DIR", "")));
    }
}
