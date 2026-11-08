package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolSchedulingSettingsTest {
    @TempDir Path root;
    @Test void defaultsAndSharedDirectoryAndEnvironmentOverride() throws Exception {
        assertEquals(new ToolSchedulingSettings(4,30),ToolSchedulingSettings.load(root,Map.of()));
        Path config=Files.createDirectory(root.resolve("config"));Files.writeString(config.resolve(".env"),"CODEY_TOOL_MAX_PARALLEL=2\nCODEY_TOOL_READ_TIMEOUT_SECONDS=12\n");
        assertEquals(new ToolSchedulingSettings(2,12),ToolSchedulingSettings.load(root,Map.of("CODEY_CONFIG_DIR",config.toString())));
        assertEquals(new ToolSchedulingSettings(1,7),ToolSchedulingSettings.load(root,Map.of("CODEY_CONFIG_DIR",config.toString(),"CODEY_TOOL_MAX_PARALLEL","1","CODEY_TOOL_READ_TIMEOUT_SECONDS","7")));
        assertEquals(new ToolSchedulingSettings(8,120),new ToolSchedulingSettings(8,120));
    }
    @ParameterizedTest @ValueSource(strings={"","0","-1","9","1.5","2147483648","secret-fixture"})
    void invalidParallelismDoesNotExposeValue(String value) {
        var error=assertThrows(ConfigException.class,()->ToolSchedulingSettings.load(root,Map.of("CODEY_TOOL_MAX_PARALLEL",value)));
        assertTrue(error.getMessage().contains("CODEY_TOOL_MAX_PARALLEL"));assertFalse(error.getMessage().contains("secret-fixture"));
    }
    @ParameterizedTest @ValueSource(strings={"","0","121","-1","abc","2147483648"})
    void invalidTimeout(String value) {assertThrows(ConfigException.class,()->ToolSchedulingSettings.load(root,Map.of("CODEY_TOOL_READ_TIMEOUT_SECONDS",value)));}
}
