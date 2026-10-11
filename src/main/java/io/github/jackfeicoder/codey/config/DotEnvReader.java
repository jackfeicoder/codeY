package io.github.jackfeicoder.codey.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads a documented, single-line subset of dotenv; does not evaluate shell syntax. */
final class DotEnvReader {
    private DotEnvReader() {
    }

    static Map<String, String> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return Map.of();
        } catch (IOException unreadable) {
            throw new ConfigException("Cannot read .env as UTF-8: " + file);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ") || line.startsWith("export\t")) {
                line = line.substring(6).stripLeading();
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw invalidLine(index + 1);
            }
            String key = line.substring(0, separator).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || values.containsKey(key)) {
                throw invalidLine(index + 1);
            }
            values.put(key, parseValue(line.substring(separator + 1).strip(), index + 1));
        }
        return Map.copyOf(values);
    }

    private static String parseValue(String value, int lineNumber) {
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            int closing = value.indexOf(quote, 1);
            if (closing < 0) {
                throw invalidLine(lineNumber);
            }
            String remaining = value.substring(closing + 1).strip();
            if (!remaining.isEmpty() && !remaining.startsWith("#")) {
                throw invalidLine(lineNumber);
            }
            return value.substring(1, closing);
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '#' && (index == 0 || Character.isWhitespace(value.charAt(index - 1)))) {
                return value.substring(0, index).stripTrailing();
            }
        }
        return value;
    }

    private static ConfigException invalidLine(int lineNumber) {
        return new ConfigException("Invalid or duplicate .env entry at line " + lineNumber
                + "; expected a unique KEY=VALUE entry with balanced quotes.");
    }
}
