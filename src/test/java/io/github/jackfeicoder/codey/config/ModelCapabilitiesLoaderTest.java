package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 临时配置验证模型身份、可选文件、有界严格解析与错误脱敏，不读取真实 .env。 */
class ModelCapabilitiesLoaderTest {
    @Test void loadsOptionalBudgetOverridesAndPreservesLegacyDefaults() throws Exception {
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":128000,\"outputReserveTokens\":2048,\"safetyMarginTokens\":0}]}");
        assertEquals(new ModelCapabilities(128000L, 2048L, 0L), load("fixture", "a"));
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":128000,\"outputReserveTokens\":2048}]}");
        assertEquals(new ModelCapabilities(128000L, 2048L, null), load("fixture", "a"));
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":128000}]}");
        assertEquals(new ModelCapabilities(128000L), load("fixture", "a"));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "1.5", "true", "\"secret-fixture\"", "-1", "9223372036854775808", "128000"})
    void rejectsInvalidOptionalBudgetValues(String value) throws Exception {
        for (String field : new String[]{"outputReserveTokens", "safetyMarginTokens"}) {
            write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":128000,\"" + field + "\":" + value + "}]}");
            String message = assertThrows(ConfigException.class, () -> load("fixture", "a")).getMessage();
            assertTrue(message.contains("models.json")); assertFalse(message.contains("secret-fixture"));
        }
    }
    @TempDir Path directory;

    private ModelCapabilities load(String alias, String id) {
        return ModelCapabilitiesLoader.load(directory, Map.of(), alias, id);
    }

    private void write(String json) throws Exception { Files.writeString(directory.resolve("models.json"), json); }

    @Test void missingFileAndUnconfiguredModelRemainUnknown() throws Exception {
        assertNull(load("fixture", "org/model-a").contextWindow());
        assertEquals("未配置", load("fixture", "org/model-a").source());
        write("{\"models\":[]}");
        assertEquals(ModelCapabilities.unknown(), load("fixture", "org/model-a"));
        write("{\"models\":[{\"profile\":\"other\",\"modelId\":\"org/model-a\",\"contextWindow\":128000}]}");
        assertNull(load("fixture", "org/model-a").contextWindow());
    }

    @Test void matchesProfileAndExactModelIdInsteadOfGuessingFromName() throws Exception {
        write("""
                {"models":[
                  {"profile":"Fixture","modelId":"org/model-a","contextWindow":128000},
                  {"profile":"fixture","modelId":"org/model-b","contextWindow":1000000},
                  {"profile":"other","modelId":"org/model-a","contextWindow":64000},
                  {"profile":"default","modelId":"local:latest","contextWindow":32768}
                ]}
                """);
        assertEquals(128000L, load("FIXTURE", "org/model-a").contextWindow());
        assertEquals(1000000L, load("fixture", "org/model-b").contextWindow());
        assertEquals(64000L, load("other", "org/model-a").contextWindow());
        assertEquals(32768L, load("default", "local:latest").contextWindow());
        assertNull(load("fixture", "org/Model-a").contextWindow());
        assertNull(load("fixture", "org/model").contextWindow());
        assertEquals("models.json（本地配置）", load("fixture", "org/model-a").source());
    }

    @Test void sharedConfigDirectoryAndExplicitReloadUseFreshImmutableValues() throws Exception {
        Path config = Files.createDirectory(directory.resolve("shared"));
        var env = Map.of("CODEY_CONFIG_DIR", "shared");
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":1}]}");
        assertNull(ModelCapabilitiesLoader.load(directory, env, "fixture", "a").contextWindow());
        Path file = config.resolve("models.json");
        Files.writeString(file, "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":128000}]}");
        var before = ModelCapabilitiesLoader.load(directory, env, "fixture", "a");
        Files.writeString(file, "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":1000000}]}");
        assertEquals(1000000L, ModelCapabilitiesLoader.load(directory, env, "fixture", "a").contextWindow());
        assertEquals(128000L, before.contextWindow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "true", "null", "\"128k\"", "9223372036854775808"})
    void rejectsInvalidCapacityTypesAndRanges(String value) throws Exception {
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":" + value + "}]}");
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{}", "{\"models\":null}", "{\"models\":{}}", "{\"models\":[null]}",
            "{\"models\":[],\"unknown\":true}", "{\"models\":[]} {}", "{\"models\":[],\"models\":[]}",
            "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\"}]}",
            "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"context_window\":128000}]}",
            "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":1,\"contextWindow\":2}]}",
            "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":1,\"extra\":true}]}"
    })
    void rejectsMalformedShapesDuplicateFieldsAndTrailingData(String json) throws Exception {
        write(json);
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
    }

    @Test void normalizedDuplicatePairAndInvalidUnselectedEntryBothFail() throws Exception {
        write("""
                {"models":[
                  {"profile":"Fixture","modelId":"a","contextWindow":128000},
                  {"profile":"fixture","modelId":"a","contextWindow":1000000}
                ]}
                """);
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
        write("""
                {"models":[
                  {"profile":"fixture","modelId":"a","contextWindow":128000},
                  {"profile":"other","modelId":"b","contextWindow":-1}
                ]}
                """);
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
    }

    @Test void errorsHideContentIncludingInvalidIdentifiers() throws Exception {
        for (String json : new String[]{
                "{secret-fixture",
                "{\"models\":[{\"profile\":\"bad-secret-fixture\",\"modelId\":\"a\",\"contextWindow\":1}]}",
                "{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"secret-fixture bad\",\"contextWindow\":1}]}"
        }) {
            write(json);
            String message = assertThrows(ConfigException.class, () -> load("fixture", "a")).getMessage();
            assertTrue(message.contains("models.json"));
            assertFalse(message.contains("secret-fixture"));
        }
    }

    @Test void rejectsOversizedFilesEntryCountsInvalidUtf8AndDirectories() throws Exception {
        write(" ".repeat(1024 * 1024 + 1));
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
        String entry = "{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":1}";
        write("{\"models\":[" + String.join(",", java.util.Collections.nCopies(1025, entry)) + "]}");
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
        byte[] prefix = "{\"models\":[{\"profile\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = java.util.Arrays.copyOf(prefix, prefix.length + 1); malformed[prefix.length] = (byte) 0xff;
        Files.write(directory.resolve("models.json"), malformed);
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
        Files.delete(directory.resolve("models.json")); Files.createDirectory(directory.resolve("models.json"));
        assertThrows(ConfigException.class, () -> load("fixture", "a"));
    }

    @Test void capacityValuePreservesUnknownAndAcceptsLongWithoutIntegerOverflow() throws Exception {
        assertNull(new ModelCapabilities(null).contextWindow());
        assertThrows(ConfigException.class, () -> new ModelCapabilities(0L));
        assertThrows(ConfigException.class, () -> new ModelCapabilities(-1L));
        write("{\"models\":[{\"profile\":\"fixture\",\"modelId\":\"a\",\"contextWindow\":9223372036854775807}]}");
        assertEquals(Long.MAX_VALUE, load("fixture", "a").contextWindow());
    }
}
