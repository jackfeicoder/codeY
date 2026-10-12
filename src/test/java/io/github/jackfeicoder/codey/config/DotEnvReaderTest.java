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

/** dotenv 解析测试：创建临时文件，验证引号、注释、BOM、格式错误和字面值保留。 */
class DotEnvReaderTest {
    /** JUnit 为本次测试创建的临时目录，与真实项目配置隔离。 */
    @TempDir
    Path directory;

    /** 验证 BOM、注释、export、引号以及值中的等号和井号解析。 */
    @Test
    void acceptsBomCommentsExportQuotesEqualsAndLiteralHash() throws IOException {
        // 临时样例文件路径，不使用用户的真实 .env。
        Path file = write("\uFEFF# UTF-8 BOM\n\n"
                + " export TOKEN = \"fixture=a#b\" # comment\n"
                + "MODEL='model with spaces'\n"
                + "URL=https://example.com/v1 # comment\n"
                + "LITERAL=abc#def\nEMPTY=\nCOMMENT=# comment\n");
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = DotEnvReader.read(file);
        assertEquals("fixture=a#b", values.get("TOKEN"));
        assertEquals("model with spaces", values.get("MODEL"));
        assertEquals("https://example.com/v1", values.get("URL"));
        assertEquals("abc#def", values.get("LITERAL"));
        assertEquals("", values.get("EMPTY"));
        assertEquals("", values.get("COMMENT"));
    }

    /** 验证反斜杠与变量表达式按字面保留。 */
    @Test
    void preservesWindowsPathsAndDoesNotExpandVariables() throws IOException {
        // 待加载的配置样例，可修改个别字段构造边界情况。
        Map<String, String> values = DotEnvReader.read(write("PATH='C:\\work\\codey'\nREF=${TOKEN}\n"));
        assertEquals("C:\\work\\codey", values.get("PATH"));
        assertEquals("${TOKEN}", values.get("REF"));
    }

    /** 验证格式错误只报告行号，不回显所在行的敏感文本。 */
    @ParameterizedTest
    @ValueSource(strings = {"TOKEN", "=fixture-secret", "1TOKEN=fixture-secret",
            "TOKEN='fixture-secret", "TOKEN=\"fixture-secret\"junk",
            "TOKEN=fixture-secret\nTOKEN=duplicate", "TOKEN.NAME=fixture-secret"})
    void malformedEntriesIdentifyLineButNeverEchoContent(String contents) throws IOException {
        // 临时样例文件路径，不使用用户的真实 .env。
        Path file = write("# header\n" + contents);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        ConfigException error = assertThrows(ConfigException.class, () -> DotEnvReader.read(file));
        assertTrue(error.getMessage().contains("line "));
        assertFalse(error.getMessage().contains("fixture-secret"));
    }

    /** 验证缺失文件返回空配置，便于环境变量独立使用。 */
    @Test
    void missingFileReturnsEmptyMap() {
        assertEquals(Map.of(), DotEnvReader.read(directory.resolve(".env")));
    }

    /** 创建并返回临时 .env 文件，contents 是本测试的样例文本。 */
    private Path write(String contents) throws IOException {
        // 临时样例文件路径，不使用用户的真实 .env。
        Path file = directory.resolve(".env");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
