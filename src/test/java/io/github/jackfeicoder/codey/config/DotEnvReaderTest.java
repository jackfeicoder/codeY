package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DotEnvReaderTest {
    @TempDir
    Path directory;

    @Test
    void acceptsBomCommentsExportQuotesEqualsAndLiteralHash() throws IOException {
        Path file = write("\uFEFF# UTF-8 BOM\n\n"
                + " export TOKEN = \"fixture=a#b\" # comment\n"
                + "MODEL='model with spaces'\n"
                + "URL=https://example.com/v1 # comment\n"
                + "LITERAL=abc#def\nEMPTY=\nCOMMENT=# comment\n");
        Map<String, String> values = DotEnvReader.read(file);
        assertEquals("fixture=a#b", values.get("TOKEN"));
        assertEquals("model with spaces", values.get("MODEL"));
        assertEquals("https://example.com/v1", values.get("URL"));
        assertEquals("abc#def", values.get("LITERAL"));
        assertEquals("", values.get("EMPTY"));
        assertEquals("", values.get("COMMENT"));
    }

    @Test
    void preservesWindowsPathsAndDoesNotExpandVariables() throws IOException {
        Map<String, String> values = DotEnvReader.read(write("PATH='C:\\work\\codey'\nREF=${TOKEN}\n"));
        assertEquals("C:\\work\\codey", values.get("PATH"));
        assertEquals("${TOKEN}", values.get("REF"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "=fixture-secret", "1TOKEN=fixture-secret",
            "TOKEN='fixture-secret", "TOKEN=\"fixture-secret\"junk",
            "TOKEN=fixture-secret\nTOKEN=duplicate", "TOKEN.NAME=fixture-secret"})
    void malformedEntriesIdentifyLineButNeverEchoContent(String contents) throws IOException {
        Path file = write("# header\n" + contents);
        ConfigException error = assertThrows(ConfigException.class, () -> DotEnvReader.read(file));
        assertTrue(error.getMessage().contains("line "));
        assertFalse(error.getMessage().contains("fixture-secret"));
    }

    @Test
    void missingFileReturnsEmptyMap() {
        assertEquals(Map.of(), DotEnvReader.read(directory.resolve(".env")));
    }

    private Path write(String contents) throws IOException {
        Path file = directory.resolve(".env");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
