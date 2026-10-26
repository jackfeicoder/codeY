package io.github.jackfeicoder.codey.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 默认值、共享目录、环境覆盖和脱敏错误；不使用用户真实配置。 */
class ContextBudgetSettingsTest {
    @TempDir Path directory;

    @Test void defaultsAndSharedConfigEnvironmentPriority() throws Exception {
        assertEquals(new ContextBudgetSettings(), ContextBudgetSettings.load(directory, Map.of()));
        var config = Files.createDirectory(directory.resolve("config"));
        Files.writeString(config.resolve(".env"), "CODEY_CONTEXT_OUTPUT_RESERVE_TOKENS=4096\nCODEY_CONTEXT_SAFETY_MARGIN_TOKENS=1024\nCODEY_CONTEXT_WARNING_RATIO=0.75\n");
        var settings = ContextBudgetSettings.load(directory, Map.of("CODEY_CONFIG_DIR", config.toString(),
                "CODEY_CONTEXT_OUTPUT_RESERVE_TOKENS", "2048"));
        assertEquals(2048, settings.outputReserveTokens()); assertEquals(1024, settings.safetyMarginTokens());
        assertEquals(new BigDecimal("0.75"), settings.warningRatio());
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "1.5", "secret-fixture", "9223372036854775808"})
    void rejectsInvalidIntegerWithoutEchoingValue(String value) {
        for (String key : new String[]{"CODEY_CONTEXT_OUTPUT_RESERVE_TOKENS", "CODEY_CONTEXT_SAFETY_MARGIN_TOKENS"}) {
            String message = assertThrows(ConfigException.class, () -> ContextBudgetSettings.load(directory, Map.of(key, value))).getMessage();
            assertTrue(message.contains(key)); assertFalse(message.contains("secret-fixture"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "0", "-0.1", "1.1", "NaN", "secret-fixture", "0.1234567", "1e-1000000"})
    void rejectsInvalidRatioAndExtremePrecision(String value) {
        String message = assertThrows(ConfigException.class, () -> ContextBudgetSettings.load(directory, Map.of("CODEY_CONTEXT_WARNING_RATIO", value))).getMessage();
        assertTrue(message.contains("CODEY_CONTEXT_WARNING_RATIO")); assertFalse(message.contains("secret-fixture"));
    }

    @Test void zeroMarginAndFullBudgetWarningAreValidButZeroOutputIsNot() {
        var settings = ContextBudgetSettings.load(directory, Map.of("CODEY_CONTEXT_SAFETY_MARGIN_TOKENS", "0", "CODEY_CONTEXT_WARNING_RATIO", "1.000000"));
        assertEquals(0, settings.safetyMarginTokens()); assertEquals(BigDecimal.ONE, settings.warningRatio());
        assertThrows(ConfigException.class, () -> new ContextBudgetSettings(0, 1, new BigDecimal("0.8")));
        assertThrows(ConfigException.class, () -> new ContextBudgetSettings(1, 0, new BigDecimal("1E-1000000")));
    }
}
