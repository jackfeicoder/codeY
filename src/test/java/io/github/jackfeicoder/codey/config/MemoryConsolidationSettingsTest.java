package io.github.jackfeicoder.codey.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MemoryConsolidationSettingsTest {
    @TempDir Path root;
    @Test void defaultsAndEnvironmentOverrideFile() throws Exception {
        assertEquals(new MemoryConsolidationSettings(),MemoryConsolidationSettings.load(root,Map.of()));
        Files.writeString(root.resolve(".env"),"CODEY_MEMORY_TIDY_ENTRIES=10\nCODEY_MEMORY_TIDY_ENABLED=false\n");
        assertEquals(new MemoryConsolidationSettings(true,10,8000,3600,10,20,16000,4096),MemoryConsolidationSettings.load(root,Map.of("CODEY_MEMORY_TIDY_ENABLED","true")));
    }
    @Test void invalidFlagsNumbersAndBoundsRejectWithoutLeakingValues() {
        assertThrows(ConfigException.class,()->MemoryConsolidationSettings.load(root,Map.of("CODEY_MEMORY_TIDY_ENABLED","yes")));
        assertThrows(ConfigException.class,()->MemoryConsolidationSettings.load(root,Map.of("CODEY_MEMORY_TIDY_ENTRIES","PRIVATE")));
        assertThrows(ConfigException.class,()->new MemoryConsolidationSettings(true,1,8000,0,1,5,16000,4096));
        assertThrows(ConfigException.class,()->new MemoryConsolidationSettings(true,50,8000,-1,1,5,16000,4096));
        assertThrows(ConfigException.class,()->new MemoryConsolidationSettings(true,50,8000,0,1,0,16000,4096));
    }
}
