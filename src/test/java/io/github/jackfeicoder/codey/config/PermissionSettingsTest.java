package io.github.jackfeicoder.codey.config;

import io.github.jackfeicoder.codey.policy.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PermissionSettingsTest {
    @TempDir Path directory;
    @Test void defaultsAndEnvironmentOverrideAreExplicit() throws Exception {
        assertEquals(new PermissionSettings(), PermissionSettings.load(directory, Map.of()));
        Files.writeString(directory.resolve(".env"), "CODEY_PERMISSION_MODE=auto\nCODEY_ALLOW_PIPED_APPROVALS=true\n");
        assertEquals(new PermissionSettings(PermissionMode.AUTO, true), PermissionSettings.load(directory, Map.of()));
        assertEquals(new PermissionSettings(PermissionMode.ASK, false), PermissionSettings.load(directory,
                Map.of("CODEY_PERMISSION_MODE", "ASK", "CODEY_ALLOW_PIPED_APPROVALS", "false")));
    }
    @ParameterizedTest @ValueSource(strings={"", "wrong", "secret-fixture"})
    void invalidModesDoNotExposeConfigurationValue(String value) {
        var failure = assertThrows(ConfigException.class, () -> PermissionSettings.load(directory, Map.of("CODEY_PERMISSION_MODE", value)));
        assertEquals("CODEY_PERMISSION_MODE must be ask, auto or full.", failure.getMessage());
    }
    @Test void booleanMustBeTrueOrFalse() {
        assertThrows(ConfigException.class, () -> PermissionSettings.load(directory, Map.of("CODEY_ALLOW_PIPED_APPROVALS", "yes")));
    }
}
