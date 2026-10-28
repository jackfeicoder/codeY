package io.github.jackfeicoder.codey.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MemoryExtractionSettingsTest {
    @TempDir Path root;
    @Test void defaultsAndEnvironmentOverrideConfigFile() throws Exception {
        assertEquals(new MemoryExtractionSettings(), MemoryExtractionSettings.load(root, Map.of()));
        Files.writeString(root.resolve(".env"), "CODEY_MEMORY_AUTO_ENABLED=false\nCODEY_MEMORY_AUTO_TIMEOUT_SECONDS=3\n");
        assertEquals(new MemoryExtractionSettings(true, 3, 4096, 1024), MemoryExtractionSettings.load(root, Map.of("CODEY_MEMORY_AUTO_ENABLED", "true")));
    }
    @Test void invalidFlagsTypesAndBoundsFailBeforeModelCall() {
        assertThrows(ConfigException.class, () -> MemoryExtractionSettings.load(root, Map.of("CODEY_MEMORY_AUTO_ENABLED", "yes")));
        assertThrows(ConfigException.class, () -> MemoryExtractionSettings.load(root, Map.of("CODEY_MEMORY_AUTO_TIMEOUT_SECONDS", "secret")));
        assertThrows(ConfigException.class, () -> new MemoryExtractionSettings(true, 0, 4096, 1024));
        assertThrows(ConfigException.class, () -> new MemoryExtractionSettings(true, 10, 100, 1024));
        assertThrows(ConfigException.class, () -> new MemoryExtractionSettings(true, 10, 4096, 10000));
    }
}
