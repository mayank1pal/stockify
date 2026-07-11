package com.stockman.scanner.engine;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.SignalStrength;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.SignalEnums.TradingStyle;
import com.stockman.scanner.model.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiBudgeterTest {

    private static final IndicatorSnapshot DUMMY_INDICATORS = new IndicatorSnapshot(
            0, 0, 0, 0, 50, 0, 0, 0, 0, 0, 0, 0, true, 0, 1.0, 0, true);

    private AiBudgeter budgeter;

    @BeforeEach
    void setUp() {
        ScannerConfig config = new ScannerConfig();
        // Default: 10 calls per minute
        budgeter = new AiBudgeter(config);
    }

    // ── Priority ordering ─────────────────────────────────────────────────────

    @Test
    void strongSwing_hasHighestPriority() {
        TradeSignal strongSwing = signal(SignalStrength.STRONG, TradingStyle.SWING);
        TradeSignal moderateIntraday = signal(SignalStrength.MODERATE, TradingStyle.INTRADAY);
        TradeSignal weakScalp = signal(SignalStrength.WEAK, TradingStyle.SCALP);

        int pStrong  = budgeter.getPriority(strongSwing);
        int pModerate = budgeter.getPriority(moderateIntraday);
        int pWeak    = budgeter.getPriority(weakScalp);

        assertThat(pStrong).isGreaterThan(pModerate);
        assertThat(pModerate).isGreaterThan(pWeak);
    }

    @Test
    void strongSwing_priority_equals130() {
        // STRONG(100) + SWING(30) = 130
        TradeSignal signal = signal(SignalStrength.STRONG, TradingStyle.SWING);
        assertThat(budgeter.getPriority(signal)).isEqualTo(130);
    }

    @Test
    void moderateIntraday_priority_equals70() {
        // MODERATE(50) + INTRADAY(20) = 70
        TradeSignal signal = signal(SignalStrength.MODERATE, TradingStyle.INTRADAY);
        assertThat(budgeter.getPriority(signal)).isEqualTo(70);
    }

    @Test
    void weakScalp_priority_equals0() {
        // WEAK(0) + SCALP(0) = 0
        TradeSignal signal = signal(SignalStrength.WEAK, TradingStyle.SCALP);
        assertThat(budgeter.getPriority(signal)).isEqualTo(0);
    }

    @Test
    void strongIntraday_priority_equals120() {
        // STRONG(100) + INTRADAY(20) = 120
        TradeSignal signal = signal(SignalStrength.STRONG, TradingStyle.INTRADAY);
        assertThat(budgeter.getPriority(signal)).isEqualTo(120);
    }

    @Test
    void moderateSwing_priority_equals80() {
        // MODERATE(50) + SWING(30) = 80
        TradeSignal signal = signal(SignalStrength.MODERATE, TradingStyle.SWING);
        assertThat(budgeter.getPriority(signal)).isEqualTo(80);
    }

    @Test
    void strongSwing_outranks_strongIntraday() {
        int pSwing    = budgeter.getPriority(signal(SignalStrength.STRONG, TradingStyle.SWING));
        int pIntraday = budgeter.getPriority(signal(SignalStrength.STRONG, TradingStyle.INTRADAY));
        assertThat(pSwing).isGreaterThan(pIntraday);
    }

    @Test
    void moderateSwing_outranks_moderateIntraday() {
        int pSwing    = budgeter.getPriority(signal(SignalStrength.MODERATE, TradingStyle.SWING));
        int pIntraday = budgeter.getPriority(signal(SignalStrength.MODERATE, TradingStyle.INTRADAY));
        assertThat(pSwing).isGreaterThan(pIntraday);
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    void tryAcquire_returnsTrue_whenBudgetAvailable() {
        // A fresh budgeter at 10/min (0.166/s) should allow the first call immediately
        assertThat(budgeter.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquire_returnsFalse_whenRateLimitExhausted() {
        // Configure a very low rate: 1 call per minute → ~0.0167 permits/second
        ScannerConfig config = new ScannerConfig();
        config.getAiEnrichment().setMaxCallsPerMinute(1);
        AiBudgeter tightBudgeter = new AiBudgeter(config);

        // Drain the first permit
        boolean first = tightBudgeter.tryAcquire();
        // Immediately try again — should be false since we just consumed the only permit
        boolean second = tightBudgeter.tryAcquire();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void tryAcquire_multipleCallsWithinBurst_respectsLimit() {
        // 2 calls/minute budgeter — Guava allows a small initial burst equal to 1 permit,
        // so the first call is granted, the second call within the same second is denied.
        ScannerConfig config = new ScannerConfig();
        config.getAiEnrichment().setMaxCallsPerMinute(2);
        AiBudgeter tightBudgeter = new AiBudgeter(config);

        int granted = 0;
        int denied  = 0;
        for (int i = 0; i < 10; i++) {
            if (tightBudgeter.tryAcquire()) {
                granted++;
            } else {
                denied++;
            }
        }

        // Should grant at most 1 permit immediately (one stored permit at creation)
        assertThat(granted).isLessThanOrEqualTo(2);
        assertThat(denied).isGreaterThan(0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TradeSignal signal(SignalStrength strength, TradingStyle style) {
        return new TradeSignal(
                "TEST:SIGNAL:" + strength + ":" + style,
                "RELIANCE", 738561L,
                SignalType.BUY, strength, style,
                2500.0, 2450.0, 2600.0,
                List.of("EMA crossover"),
                DUMMY_INDICATORS,
                Instant.now()
        );
    }
}
