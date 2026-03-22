package com.stockman.scanner.engine;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.model.SignalEnums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalCooldownManagerTest {

    private static final String SYMBOL = "RELIANCE";

    private SignalCooldownManager cooldownManager;

    @BeforeEach
    void setUp() {
        ScannerConfig config = new ScannerConfig();
        // Default cooldown: scalp=5min, intraday=15min, swing=60min
        cooldownManager = new SignalCooldownManager(config);
    }

    // ── Basic state machine ────────────────────────────────────────────────────

    @Test
    void firstSignal_allowed() {
        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertThat(canEmit).isTrue();
    }

    @Test
    void duplicateSignal_blocked() {
        // Emit first signal
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // Trying to emit the same type again should be blocked
        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertThat(canEmit).isFalse();
    }

    @Test
    void oppositeSignal_moderateStrength_allowed() {
        // Record a BUY signal
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // SELL with MODERATE strength should bypass cooldown
        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.MODERATE);
        assertThat(canEmit).isTrue();
    }

    @Test
    void oppositeSignal_strongStrength_allowed() {
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.STRONG);
        assertThat(canEmit).isTrue();
    }

    @Test
    void oppositeSignal_weakStrength_blocked() {
        // Record a BUY signal
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // SELL with WEAK strength must respect cooldown → blocked
        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.WEAK);
        assertThat(canEmit).isFalse();
    }

    @Test
    void differentTimeframe_notBlocked() {
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // Different timeframe = different key → should NOT be blocked
        boolean canEmit = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.SWING, "15m", SignalType.BUY, SignalStrength.MODERATE);
        assertThat(canEmit).isTrue();
    }

    @Test
    void differentSymbol_notBlocked() {
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // Different symbol → should NOT be blocked
        boolean canEmit = cooldownManager.canEmitSignal(
            "HDFC", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertThat(canEmit).isTrue();
    }

    // ── State transitions ─────────────────────────────────────────────────────

    @Test
    void directReversal_buyThenSell_transitionsCorrectly() {
        // BUY → active
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // Opposite SELL at MODERATE strength is allowed
        boolean canSell = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.MODERATE);
        assertThat(canSell).isTrue();

        // Record the SELL
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.MODERATE);

        // After recording SELL, another BUY (weak) should be blocked (now in SELL_ACTIVE / COOLDOWN)
        boolean canBuyAgain = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.WEAK);
        assertThat(canBuyAgain).isFalse();
    }

    @Test
    void sameBuySignalAfterSellRecord_withModerateStrength_allowed() {
        // BUY → record → SELL (moderate) → record SELL
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.SCALP, "1m", SignalType.BUY, SignalStrength.MODERATE);
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.SCALP, "1m", SignalType.SELL, SignalStrength.MODERATE);

        // Another BUY with MODERATE strength should bypass the SELL_ACTIVE cooldown
        boolean canBuy = cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.SCALP, "1m", SignalType.BUY, SignalStrength.MODERATE);
        assertThat(canBuy).isTrue();
    }

    @Test
    void canEmitAndRecord_idempotent_multipleCalls() {
        // Verify canEmitSignal does NOT change state (only recordSignal does)
        assertThat(cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE)).isTrue();
        assertThat(cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE)).isTrue();

        // Still allowed because record was never called
        cooldownManager.recordSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);

        // Now blocked
        assertThat(cooldownManager.canEmitSignal(
            SYMBOL, TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE)).isFalse();
    }
}
