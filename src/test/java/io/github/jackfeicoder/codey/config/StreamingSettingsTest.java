package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSettingsTest {
    @TempDir Path directory;
    @Test void defaultsAndEnvironmentPriorityFollowSharedConfigDirectory() throws Exception {
        assertEquals(new StreamingSettings(true, true), StreamingSettings.load(directory, Map.of()));
        Path config = Files.createDirectory(directory.resolve("config"));
        Files.writeString(config.resolve(".env"), "CODEY_LLM_STREAM=false\nCODEY_LLM_STREAM_INCLUDE_USAGE=false\n");
        assertEquals(new StreamingSettings(false, false), StreamingSettings.load(directory, Map.of("CODEY_CONFIG_DIR", config.toString())));
        assertEquals(new StreamingSettings(true, false), StreamingSettings.load(directory,
                Map.of("CODEY_CONFIG_DIR", config.toString(), "CODEY_LLM_STREAM", "TRUE")));
    }
    @ParameterizedTest @ValueSource(strings={"", "1", "secret-fixture"})
    void flagsRequireBooleansAndErrorsDoNotEchoValues(String value) {
        for (String key : java.util.List.of("CODEY_LLM_STREAM", "CODEY_LLM_STREAM_INCLUDE_USAGE")) {
            var error = assertThrows(ConfigException.class, () -> StreamingSettings.load(directory, Map.of(key, value)));
            assertEquals(key + " must be true or false.", error.getMessage());
        }
    }
}
