package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelProfilesTest {
    @TempDir
    Path directory;

    @Test
    void keepsSelectedAndOtherProfilesSeparate() {
        ModelProfiles profiles = load(settings());
        assertEquals(List.of("cloud", "local", "gateway"), profiles.profileNames());
        assertEquals("cloud", profiles.activeProfile());
        assertEquals("cloud-model", profiles.activeConfig().model());
        assertEquals("fixture-cloud-key", profiles.activeConfig().apiKey());
        assertEquals("gateway-model", profiles.configFor("gateway").model());
        assertEquals("fixture-gateway-key", profiles.configFor("gateway").apiKey());
    }

    @Test
    void cliOverrideWinsWhenDefaultIsMissingOrInvalid() {
        Map<String, String> values = settings();
        values.put("CODEY_ACTIVE_PROFILE", "unknown");
        assertEquals("local", ConfigLoader.loadProfiles(directory, values, "local").activeProfile());
        values.remove("CODEY_ACTIVE_PROFILE");
        assertEquals(AuthMode.NONE, ConfigLoader.loadProfiles(directory, values, "local").activeConfig().authMode());
    }

    @Test
    void environmentOverridesFileFieldsAndDefaultSelection() throws IOException {
        Files.writeString(directory.resolve(".env"), """
                CODEY_PROFILE_NAMES=cloud,local
                CODEY_ACTIVE_PROFILE=cloud
                CODEY_PROFILE_LOCAL_BASE_URL=http://localhost:8000/v1
                CODEY_PROFILE_LOCAL_AUTH=none
                CODEY_PROFILE_LOCAL_MODEL=file-model
                """);
        ModelProfiles profiles = load(Map.of("CODEY_ACTIVE_PROFILE", "local",
                "CODEY_PROFILE_LOCAL_MODEL", "environment-model"));
        assertEquals("local", profiles.activeProfile());
        assertEquals("environment-model", profiles.activeConfig().model());
    }

    @Test
    void aliasesAreCaseInsensitive() {
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", "Cloud,LOCAL,Gateway");
        values.put("CODEY_ACTIVE_PROFILE", "CLOUD");
        assertEquals("cloud", load(values).activeProfile());
        assertEquals("local", load(values).select(" LOCAL ").activeProfile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "cloud,", "cloud,,local", "cloud,CLOUD", "cloud-name", "1cloud", "cloud.local"})
    void rejectsInvalidOrDuplicateAliasLists(String names) {
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", names);
        assertThrows(ConfigException.class, () -> load(values));
    }

    @Test
    void rejectsUnknownOrMissingActiveProfile() {
        Map<String, String> values = settings();
        values.put("CODEY_ACTIVE_PROFILE", "unknown");
        assertThrows(ConfigException.class, () -> load(values));
        values.remove("CODEY_ACTIVE_PROFILE");
        assertTrue(assertThrows(ConfigException.class, () -> load(values)).getMessage().contains("CODEY_ACTIVE_PROFILE"));
    }

    @Test
    void noneAuthAllowsMissingKeyAndDiscardsConfiguredKey() {
        Map<String, String> values = settings();
        assertEquals("", load(values).configFor("local").apiKey());
        values.put("CODEY_PROFILE_LOCAL_API_KEY", "unused-fixture-key");
        LlmConfig local = load(values).configFor("local");
        assertEquals("", local.apiKey());
        assertEquals(AuthMode.NONE, local.authMode());
        assertFalse(local.toString().contains("unused-fixture-key"));
    }

    @Test
    void missingBearerKeyUsesProfileSpecificFieldAndNeverLegacyFallback() {
        Map<String, String> values = settings();
        values.remove("CODEY_PROFILE_CLOUD_API_KEY");
        values.put("CODEY_LLM_API_KEY", "legacy-fixture-key");
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_API_KEY"));
        assertFalse(error.getMessage().contains("legacy-fixture-key"));
    }

    @Test
    void inactiveIncompleteProfileDoesNotBlockSelectedModel() {
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_NAMES", "cloud,local,gateway,incomplete");
        ModelProfiles profiles = load(values);
        assertEquals("cloud-model", profiles.activeConfig().model());
        assertEquals(4, profiles.profileNames().size());
        assertThrows(ConfigException.class, () -> profiles.configFor("incomplete"));
    }

    @Test
    void failedSelectionLeavesOriginalActiveProfileUnchanged() {
        Map<String, String> values = settings();
        values.remove("CODEY_PROFILE_GATEWAY_API_KEY");
        ModelProfiles original = load(values);
        assertThrows(ConfigException.class, () -> original.select("gateway"));
        assertEquals("cloud", original.activeProfile());
        assertEquals("fixture-cloud-key", original.activeConfig().apiKey());
        assertThrows(ConfigException.class, () -> original.select("unknown"));
    }

    @Test
    void successfulSelectionReturnsIndependentImmutableSnapshot() {
        Map<String, String> values = settings();
        ModelProfiles original = load(values);
        ModelProfiles selected = original.select("gateway");
        values.put("CODEY_PROFILE_GATEWAY_API_KEY", "changed-after-load");
        assertEquals("cloud", original.activeProfile());
        assertEquals("gateway", selected.activeProfile());
        assertEquals("fixture-gateway-key", selected.activeConfig().apiKey());
        assertThrows(UnsupportedOperationException.class, () -> selected.profileNames().add("other"));
        assertFalse(selected.toString().contains("fixture-gateway-key"));
    }

    @Test
    void profileTimeoutOverridesGlobalDefault() {
        Map<String, String> values = settings();
        values.put("CODEY_LLM_TIMEOUT_SECONDS", "45");
        values.put("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS", "30");
        ModelProfiles profiles = load(values);
        assertEquals(Duration.ofSeconds(45), profiles.activeConfig().timeout());
        assertEquals(Duration.ofSeconds(30), profiles.configFor("local").timeout());
    }

    @Test
    void blankProfileTimeoutDoesNotFallBack() {
        Map<String, String> values = settings();
        values.put("CODEY_LLM_TIMEOUT_SECONDS", "45");
        values.put("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS", "");
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).configFor("local"));
        assertTrue(error.getMessage().contains("CODEY_PROFILE_LOCAL_TIMEOUT_SECONDS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "basic", "wrong-fixture-secret"})
    void rejectsUnsupportedAuthWithoutEchoingValue(String auth) {
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_CLOUD_AUTH", auth);
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_AUTH"));
        assertFalse(error.getMessage().contains("wrong-fixture-secret"));
    }

    @Test
    void validationErrorsUseNamedUrlField() {
        Map<String, String> values = settings();
        values.put("CODEY_PROFILE_CLOUD_BASE_URL", "ftp://example.com/v1");
        ConfigException error = assertThrows(ConfigException.class, () -> load(values).activeConfig());
        assertTrue(error.getMessage().contains("CODEY_PROFILE_CLOUD_BASE_URL"));
    }

    @Test
    void legacySingleModelBecomesDefaultProfile() {
        Map<String, String> values = Map.of("CODEY_LLM_BASE_URL", "https://example.com/v1",
                "CODEY_LLM_API_KEY", "legacy-fixture-key", "CODEY_LLM_MODEL", "legacy-model");
        ModelProfiles profiles = load(values);
        assertEquals(List.of("default"), profiles.profileNames());
        assertEquals("default", profiles.activeProfile());
        assertEquals("legacy-model", profiles.activeConfig().model());
        assertEquals(AuthMode.BEARER, profiles.activeConfig().authMode());
        assertEquals("default", ConfigLoader.loadProfiles(directory, values, "default").activeProfile());
    }

    private ModelProfiles load(Map<String, String> values) {
        return ConfigLoader.loadProfiles(directory, values);
    }

    private static Map<String, String> settings() {
        Map<String, String> values = new HashMap<>();
        values.put("CODEY_PROFILE_NAMES", "cloud,local,gateway");
        values.put("CODEY_ACTIVE_PROFILE", "cloud");
        values.put("CODEY_PROFILE_CLOUD_BASE_URL", "https://cloud.example.com/v1");
        values.put("CODEY_PROFILE_CLOUD_API_KEY", "fixture-cloud-key");
        values.put("CODEY_PROFILE_CLOUD_MODEL", "cloud-model");
        values.put("CODEY_PROFILE_LOCAL_BASE_URL", "http://localhost:8000/v1");
        values.put("CODEY_PROFILE_LOCAL_MODEL", "local-model");
        values.put("CODEY_PROFILE_LOCAL_AUTH", "none");
        values.put("CODEY_PROFILE_GATEWAY_BASE_URL", "https://gateway.example.com/v1");
        values.put("CODEY_PROFILE_GATEWAY_API_KEY", "fixture-gateway-key");
        values.put("CODEY_PROFILE_GATEWAY_MODEL", "gateway-model");
        return values;
    }
}
