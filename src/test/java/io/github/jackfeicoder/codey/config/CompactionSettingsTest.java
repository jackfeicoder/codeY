package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CompactionSettingsTest {
    @TempDir Path directory;
    @Test void defaultsAndEnvironmentOverridesSharedConfiguration() throws Exception {
        assertEquals(new CompactionSettings(), CompactionSettings.load(directory, Map.of()));
        var shared = Files.createDirectory(directory.resolve("shared"));
        Files.writeString(shared.resolve(".env"), "CODEY_CONTEXT_COMPACTION_ENABLED=false\nCODEY_CONTEXT_KEEP_RECENT_TURNS=2\nCODEY_CONTEXT_COMPACTION_TARGET_RATIO=0.3\n");
        var value = CompactionSettings.load(directory, Map.of("CODEY_CONFIG_DIR", shared.toString(), "CODEY_CONTEXT_COMPACTION_ENABLED", "true"));
        assertTrue(value.enabled()); assertEquals(2, value.keepRecentTurns()); assertEquals(new BigDecimal("0.3"), value.targetRatio());
    }
    @ParameterizedTest @ValueSource(strings = {"", "secret-fixture", "0", "-0.1", "1.1", "NaN", "0.1234567", "1e-99999"})
    void invalidRatiosDoNotEchoConfiguration(String value) {
        for (String key : new String[]{"CODEY_CONTEXT_COMPACTION_TRIGGER_RATIO", "CODEY_CONTEXT_COMPACTION_TARGET_RATIO"}) {
            var failed = assertThrows(ConfigException.class, () -> CompactionSettings.load(directory, Map.of(key, value)));
            assertFalse(failed.getMessage().contains("secret-fixture"));
        }
    }
    @Test void enforcesWatermarkOrderRangesAndExplicitFlags() {
        assertThrows(ConfigException.class, () -> CompactionSettings.load(directory, Map.of("CODEY_CONTEXT_COMPACTION_TARGET_RATIO", "0.8")));
        for (var entry : Map.of("CODEY_CONTEXT_KEEP_RECENT_TURNS", "1", "CODEY_CONTEXT_SUMMARY_MAX_TOKENS", "127",
                "CODEY_CONTEXT_COMPACTION_MAX_CALLS", "0", "CODEY_CONTEXT_COMPACTION_TIMEOUT_SECONDS", "301",
                "CODEY_CONTEXT_COMPACTION_ENABLED", "yes").entrySet()) {
            assertThrows(ConfigException.class, () -> CompactionSettings.load(directory, Map.of(entry.getKey(), entry.getValue())));
        }
        assertThrows(ConfigException.class, () -> CompactionSettings.load(directory, Map.of("CODEY_CONTEXT_COMPACTION_MAX_CALLS", "2147483648")));
    }
}
