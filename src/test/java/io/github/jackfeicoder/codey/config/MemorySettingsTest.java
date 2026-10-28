package io.github.jackfeicoder.codey.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MemorySettingsTest {
    @TempDir Path root;
    @Test void defaultsAndEnvironmentOverrideDotEnv() throws Exception {
        assertEquals(new MemorySettings(), MemorySettings.load(root, Map.of()));
        Files.writeString(root.resolve(".env"), "CODEY_MEMORY_ALWAYS_TOKENS=500\nCODEY_MEMORY_RECALL_TOKENS=900\nCODEY_MEMORY_MAX_RESULTS=3\n");
        assertEquals(new MemorySettings(500, 1200, 3), MemorySettings.load(root, Map.of("CODEY_MEMORY_RECALL_TOKENS", "1200")));
    }
    @Test void invalidAndOutOfRangeConfigurationIsExplicit() {
        assertThrows(ConfigException.class, () -> MemorySettings.load(root, Map.of("CODEY_MEMORY_ALWAYS_TOKENS", "oops")));
        assertThrows(ConfigException.class, () -> new MemorySettings(0, 2000, 5));
        assertThrows(ConfigException.class, () -> new MemorySettings(1000, -1, 5));
        assertThrows(ConfigException.class, () -> new MemorySettings(1000, 2000, 21));
    }
}
