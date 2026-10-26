package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** 纯策略边界验证：输入预算、预警/阻止、未知容量、模型覆盖以及整数溢出。 */
class ContextBudgetPolicyTest {
    private final ContextBudgetSettings settings = new ContextBudgetSettings(8000, 4000, new BigDecimal("0.8"));

    @ParameterizedTest @CsvSource({"0,NORMAL", "92799,NORMAL", "92800,WARNING", "116000,WARNING", "116001,EXCEEDED"})
    void usesInputBudgetRatherThanWindowForWarningAndAllowsExactBudget(long estimate, String expected) {
        var result = new ContextBudgetPolicy(new ModelCapabilities(128000L), settings).evaluate(estimate);
        assertEquals(116000L, result.inputBudget()); assertEquals(92800L, result.warningThreshold());
        assertEquals(ContextBudgetPolicy.State.valueOf(expected), result.state());
        assertEquals(116000L - estimate, result.remainingTokens());
        assertEquals(!expected.equals("EXCEEDED"), result.canSend());
    }

    @Test void resolvesEachOptionalOverrideAgainstGlobalDefaults() {
        var defaults = new ContextBudgetPolicy(new ModelCapabilities(128000L), new ContextBudgetSettings()).evaluate(0);
        assertEquals(115712L, defaults.inputBudget());
        var both = new ContextBudgetPolicy(new ModelCapabilities(128000L, 2000L, 0L), settings).evaluate(100);
        assertEquals(126000L, both.inputBudget()); assertEquals(125900L, both.remainingTokens());
        assertEquals(122000L, new ContextBudgetPolicy(new ModelCapabilities(128000L, 2000L, null), settings).evaluate(0).inputBudget());
        assertEquals(120000L, new ContextBudgetPolicy(new ModelCapabilities(128000L, null, 0L), settings).evaluate(0).inputBudget());
    }

    @Test void unknownCapacitySkipsChecksEvenForVeryLargeInputButKeepsReserveSettings() {
        var result = new ContextBudgetPolicy(ModelCapabilities.unknown(), settings).evaluate(Long.MAX_VALUE);
        assertEquals(ContextBudgetPolicy.State.UNKNOWN, result.state()); assertTrue(result.canSend());
        assertNull(result.inputBudget()); assertNull(result.remainingTokens()); assertNull(result.warningThreshold());
        assertEquals(8000, result.outputReserveTokens());
    }

    @Test void roundsWarningUpAndHandlesLongBoundariesWithoutFloatOrSumOverflow() {
        var policy = new ContextBudgetPolicy(new ModelCapabilities(10L), new ContextBudgetSettings(1, 0, new BigDecimal("0.8")));
        assertEquals(8L, policy.evaluate(0).warningThreshold());
        assertEquals(ContextBudgetPolicy.State.NORMAL, policy.evaluate(7).state());
        assertEquals(ContextBudgetPolicy.State.WARNING, policy.evaluate(8).state());
        var huge = new ContextBudgetPolicy(new ModelCapabilities(Long.MAX_VALUE), new ContextBudgetSettings(1, 0, BigDecimal.ONE));
        assertEquals(Long.MAX_VALUE - 1, huge.evaluate(0).inputBudget());
        assertEquals(-1L, huge.evaluate(Long.MAX_VALUE).remainingTokens());
        assertThrows(ConfigException.class, () -> new ContextBudgetPolicy(new ModelCapabilities(Long.MAX_VALUE),
                new ContextBudgetSettings(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, new BigDecimal("0.8"))));
    }

    @Test void invalidReservesAreConfigurationErrorsAndNegativeEstimatesAreRejected() {
        assertThrows(ConfigException.class, () -> new ContextBudgetPolicy(new ModelCapabilities(12000L), settings));
        assertThrows(ConfigException.class, () -> new ModelCapabilities(12000L, 12000L, 0L));
        assertThrows(ConfigException.class, () -> new ModelCapabilities(12000L, 8000L, 4000L));
        assertThrows(ConfigException.class, () -> new ModelCapabilities(12000L, -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> ContextBudgetPolicy.unknown().evaluate(-1));
    }
}
