package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void loadsFileAndUsesDefaultTimeout() throws IOException {
        writeEnv("""
                CODEY_LLM_BASE_URL=https://example.com/v1/
                CODEY_LLM_API_KEY=fixture-api-key
                CODEY_LLM_MODEL=fixture-model
                """);
        LlmConfig config = ConfigLoader.load(directory, Map.of());
        assertEquals(URI.create("https://example.com/v1"), config.baseUrl());
        assertEquals("fixture-api-key", config.apiKey());
        assertEquals("fixture-model", config.model());
        assertEquals(Duration.ofSeconds(120), config.timeout());
    }

    @Test
    void environmentOverridesFileWithoutChangingCallerMap() throws IOException {
        writeEnv("""
                CODEY_LLM_BASE_URL=https://example.com/v1
                CODEY_LLM_API_KEY=file-key
                CODEY_LLM_MODEL=file-model
                CODEY_LLM_TIMEOUT_SECONDS=30
                """);
        Map<String, String> environment = Map.of("CODEY_LLM_API_KEY", "environment-key",
                "CODEY_LLM_TIMEOUT_SECONDS", "45");
        LlmConfig config = ConfigLoader.load(directory, environment);
        assertEquals("environment-key", config.apiKey());
        assertEquals("file-model", config.model());
        assertEquals(Duration.ofSeconds(45), config.timeout());
        assertEquals(2, environment.size());
    }

    @Test
    void blankEnvironmentValueDoesNotFallBackToFile() throws IOException {
        writeEnv("CODEY_LLM_API_KEY=file-key\n");
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_API_KEY", " ");
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_API_KEY"));
        assertFalse(error.getMessage().contains("file-key"));
    }

    @Test
    void fileIsOptionalWhenEnvironmentIsComplete() {
        assertEquals("fixture-model", ConfigLoader.load(directory, validEnvironment()).model());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CODEY_LLM_BASE_URL", "CODEY_LLM_API_KEY", "CODEY_LLM_MODEL"})
    void missingRequiredFieldsIdentifyOnlyTheField(String field) {
        Map<String, String> environment = validEnvironment();
        environment.remove(field);
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains(field));
        assertFalse(error.getMessage().contains("fixture-api-key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "", "99999999999999999999"})
    void rejectsInvalidTimeout(String timeout) {
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_TIMEOUT_SECONDS", timeout);
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_TIMEOUT_SECONDS"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://example.com/v1", "https:///v1",
            "https://user:fixture-api-key@example.com/v1",
            "https://example.com/v1?key=fixture-api-key", "https://example.com/v1#fragment"})
    void rejectsInvalidOrCredentialBearingBaseUrlsWithoutEchoingValues(String url) {
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_BASE_URL", url);
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(directory, environment));
        assertTrue(error.getMessage().contains("CODEY_LLM_BASE_URL"));
        assertFalse(error.getMessage().contains("fixture-api-key"));
    }

    @Test
    void localhostHttpIsAllowedForLocalCompatibleServers() {
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_BASE_URL", "http://localhost:11434/v1");
        assertEquals("localhost", ConfigLoader.load(directory, environment).baseUrl().getHost());
    }

    @Test
    void examplePlaceholdersAreRejected() {
        Map<String, String> environment = validEnvironment();
        environment.put("CODEY_LLM_API_KEY", "YOUR_API_KEY");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, environment));
        environment.put("CODEY_LLM_API_KEY", "fixture-api-key");
        environment.put("CODEY_LLM_MODEL", "YOUR_MODEL");
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, environment));
    }

    @Test
    void toStringRedactsApiKey() {
        LlmConfig config = ConfigLoader.load(directory, validEnvironment());
        assertTrue(config.toString().contains("apiKey=<redacted>"));
        assertFalse(config.toString().contains("fixture-api-key"));
    }

    @Test
    void directRecordConstructionAlsoValidates() {
        assertThrows(ConfigException.class, () -> new LlmConfig(URI.create("https://example.com"),
                "", "fixture-model", Duration.ofSeconds(120)));
    }

    @Test
    void unreadableFileDoesNotBehaveLikeMissingFile() throws IOException {
        Files.createDirectory(directory.resolve(".env"));
        assertThrows(ConfigException.class, () -> ConfigLoader.load(directory, validEnvironment()));
    }

    private void writeEnv(String text) throws IOException {
        Files.writeString(directory.resolve(".env"), text, StandardCharsets.UTF_8);
    }

    private static Map<String, String> validEnvironment() {
        return new HashMap<>(Map.of("CODEY_LLM_BASE_URL", "https://example.com/v1",
                "CODEY_LLM_API_KEY", "fixture-api-key", "CODEY_LLM_MODEL", "fixture-model"));
    }
}
