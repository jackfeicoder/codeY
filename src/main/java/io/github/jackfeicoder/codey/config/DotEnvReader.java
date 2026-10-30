package io.github.jackfeicoder.codey.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 UTF-8 .env 文件解析为键值表；支持单行值、引号、注释和 export 前缀。
 * 不执行 shell 命令或变量插值，缺失文件可由进程环境补全配置。
 */
public final class DotEnvReader {
    /** 文件解析工具只通过静态方法使用。 */
    private DotEnvReader() {
    }

    /** 逐行解析文件；格式错误报告行号，结果返回不可修改的 Map。 */
    public static Map<String, String> read(Path file) {
        List<String> lines; // 文件原始行，保留顺序以定位错误。
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return Map.of();
        } catch (IOException unreadable) {
            throw new ConfigException("Cannot read .env as UTF-8: " + file);
        }
        Map<String, String> values = new LinkedHashMap<>(); // 已解析字段，同时用于检测重复键。
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index); // index 从 0 开始，错误提示行号使用 index + 1。
            if (index == 0 && line.startsWith("\uFEFF")) {
                // 部分编辑器写入 UTF-8 BOM，先移除后再识别注释或字段。
                line = line.substring(1);
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ") || line.startsWith("export\t")) {
                line = line.substring(6).stripLeading();
            }
            int separator = line.indexOf('='); // 只分割第一个等号，保留值中后续的等号。
            if (separator < 1) {
                throw invalidLine(index + 1);
            }
            String key = line.substring(0, separator).strip(); // 等号前的配置字段名。
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || values.containsKey(key)) {
                throw invalidLine(index + 1);
            }
            values.put(key, parseValue(line.substring(separator + 1).strip(), index + 1));
        }
        return Map.copyOf(values);
    }

    /** 去除包围值的引号和行末注释；value 为等号右侧，lineNumber 用于报错。 */
    private static String parseValue(String value, int lineNumber) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0); // 单引号或双引号，闭合时必须使用同一种。
            int closing = value.indexOf(quote, 1); // 当前简化语法不处理引号转义。
            if (closing < 0) {
                throw invalidLine(lineNumber);
            }
            String remaining = value.substring(closing + 1).strip(); // 闭合引号之后仅允许空白或注释。
            if (!remaining.isEmpty() && !remaining.startsWith("#")) {
                throw invalidLine(lineNumber);
            }
            return value.substring(1, closing);
        }
        for (int index = 0; index < value.length(); index++) {
            // 无引号的 # 仅在开头或前面有空白时表示注释，abc#def 会保留。
            if (value.charAt(index) == '#' && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return value.substring(0, index).stripTrailing();
            }
        }
        return value;
    }

    /** 统一生成不含原始行内容的格式错误，避免日志暴露密钥。 */
    private static ConfigException invalidLine(int lineNumber) {
        return new ConfigException("Invalid or duplicate .env entry at line " + lineNumber
                + "; expected a unique KEY=VALUE entry with balanced quotes.");
    }
}
