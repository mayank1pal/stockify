package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.FundamentalData;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.*;
import com.stockman.scanner.model.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SignalDetectorTest {

    private static final long INSTRUMENT = 12345L;
    private static final String SYMBOL = "RELIANCE";
    private static final Instant CANDLE_TIME = Instant.parse("2026-03-22T04:15:00Z");

    private SignalDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SignalDetector();
    }

    // ── Helper builders ────────────────────────────────────────────────────────

    private Candle candle(double close) {
        return new Candle(INSTRUMENT, CANDLE_TIME, close, close + 1, close - 1, close, 10_000L, close);
    }

    /**
     * Build a "neutral" IndicatorSnapshot where no rules fire.
     * EMA9 < EMA21, RSI in middle range, price < VWAP, SuperTrend bearish.
     */
    private IndicatorSnapshot neutralSnapshot(double close) {
        return new IndicatorSnapshot(
            /* ema9 */ close - 2,
            /* ema21 */ close + 2,
            /* ema50 */ close,
            /* ema200 */ close,
            /* rsi */ 50.0,
            /* macdLine */ 0.0,
            /* macdSignal */ 0.0,
            /* macdHistogram */ 0.0,
            /* stochasticK */ 50.0,
            /* stochasticD */ 50.0,
            /* vwap */ close + 5,   // price < vwap
            /* superTrend */ close + 3,
            /* superTrendBullish */ false,
            /* obv */ 1_000_000.0,
            /* rvol */ 1.0,
            /* atr */ 2.0,
            /* warmedUp */ true
        );
    }

    private FundamentalData fundamentals() {
        return new FundamentalData(SYMBOL, INSTRUMENT, null, "NSE", "Energy",
            1, null, null, null, null, null, null, null, null, null, null,
            com.stockman.scanner.model.SignalEnums.DataQuality.FRESH);
    }

    // ── Warmup guard ──────────────────────────────────────────────────────────

    @Test
    void notWarmedUp_returnsEmpty() {
        double close = 100.0;
        IndicatorSnapshot notReady = new IndicatorSnapshot(
            close, close, close, close, 50.0,
            0.0, 0.0, 0.0, 50.0, 50.0,
            close, close, false, 0.0, 1.0, 2.0,
            /* warmedUp */ false
        );
        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", notReady, notReady, fundamentals());
        assertThat(result).isEmpty();
    }

    // ── BUY rules ─────────────────────────────────────────────────────────────

    @Test
    void emaCrossoverAbove_withVolumeSpike_generatesBuySignal() {
        double close = 100.0;
        Candle c = candle(close);

        // Previous: EMA9 below EMA21
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 1, close + 1, close, close,    // ema9 < ema21 (no cross yet)
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, 2.0, true
        );

        // Current: EMA9 above EMA21 (crossover) + volume spike rvol > 2.0
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close + 1, close - 1, close, close,    // ema9 > ema21 (crossover!)
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 2.5, 2.0, true             // rvol=2.5 > 2.0
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, c, "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        TradeSignal signal = result.get();
        assertThat(signal.type()).isEqualTo(SignalType.BUY);
        assertThat(signal.style()).isEqualTo(TradingStyle.INTRADAY);
        assertThat(signal.symbol()).isEqualTo(SYMBOL);
        assertThat(signal.triggerReasons()).anyMatch(r -> r.contains("EMA"));
    }

    @Test
    void noCrossover_noVolumeSpike_returnsEmpty() {
        double close = 100.0;
        // Both snapshots have EMA9 < EMA21 — no crossover
        IndicatorSnapshot prev = neutralSnapshot(close);
        IndicatorSnapshot curr = neutralSnapshot(close);

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isEmpty();
    }

    @Test
    void rsiCrossAbove30_generatesBuySignal() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0,                                  // RSI < 30 (oversold)
            0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, 2.0, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0,                                  // RSI >= 30 (crossed above)
            0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, 2.0, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(SignalType.BUY);
        assertThat(result.get().triggerReasons()).anyMatch(r -> r.contains("RSI"));
    }

    @Test
    void priceAboveVwap_withOBVRising_generatesBuySignal() {
        double close = 110.0;
        double vwap = 100.0; // close > vwap

        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            vwap, close + 3, false,
            500_000.0, 1.0, 2.0, true            // obv = 500k
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            vwap, close + 3, false,
            600_000.0, 1.0, 2.0, true            // obv = 600k (rising)
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(SignalType.BUY);
        assertThat(result.get().triggerReasons()).anyMatch(r -> r.contains("OBV") || r.contains("VWAP"));
    }

    @Test
    void superTrendFlipsBullish_generatesBuySignal() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3,
            /* superTrendBullish */ false,         // was bearish
            900_000.0, 1.0, 2.0, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close - 3,
            /* superTrendBullish */ true,           // flipped bullish!
            900_000.0, 1.0, 2.0, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(SignalType.BUY);
        assertThat(result.get().triggerReasons()).anyMatch(r -> r.contains("SuperTrend"));
    }

    // ── SELL rules ────────────────────────────────────────────────────────────

    @Test
    void emaCrossBelow_withVolumeSpike_generatesSellSignal() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close + 1, close - 1, close, close,   // ema9 > ema21 (no cross yet)
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 1.0, 2.0, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 1, close + 1, close, close,   // ema9 < ema21 (crossover!)
            50.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 2.5, 2.0, true             // rvol=2.5 > 2.0
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(SignalType.SELL);
    }

    @Test
    void rsiCrossBelow70_generatesSellSignal() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close + 2, close - 2, close, close,
            72.0,                                  // RSI > 70 (overbought)
            0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 1.0, 2.0, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close + 2, close - 2, close, close,
            68.0,                                  // RSI <= 70 (crossed below)
            0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 1.0, 2.0, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(SignalType.SELL);
    }

    // ── Strength ──────────────────────────────────────────────────────────────

    @Test
    void twoRulesTrigger_strongSignal() {
        double close = 100.0;
        double vwap = 90.0; // close > vwap for VWAP+OBV rule

        // Prev: EMA9 < EMA21 AND RSI < 30
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 1, close + 1, close, close,
            28.0,                                  // RSI < 30
            0.0, 0.0, 0.0, 50.0, 50.0,
            vwap, close + 3, false,
            500_000.0, 1.0, 2.0, true
        );

        // Curr: EMA9 > EMA21 (crossover) + RSI >= 30 (cross above) + rvol spike
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close + 1, close - 1, close, close,   // EMA crossover
            32.0,                                  // RSI crossed above 30
            0.0, 0.0, 0.0, 50.0, 50.0,
            vwap, close + 3, false,
            500_000.0, 2.5, 2.0, true             // rvol spike for EMA rule
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().strength()).isEqualTo(SignalStrength.STRONG);
    }

    @Test
    void oneRuleTriggers_moderateSignal() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, 2.0, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0,                                  // Only RSI crossover
            0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, 2.0, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().strength()).isEqualTo(SignalStrength.MODERATE);
    }

    // ── Stop-loss & target ────────────────────────────────────────────────────

    @Test
    void buySignal_stopLossAndTargetCalculatedCorrectly() {
        double close = 100.0;
        double atr = 3.0;
        // Use RSI crossover to trigger a BUY
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, atr, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false,
            900_000.0, 1.0, atr, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        TradeSignal signal = result.get();
        double expectedStopLoss = close - 2 * atr;          // entry - 2×ATR
        double riskPerShare = close - expectedStopLoss;
        double expectedTarget = close + 2 * riskPerShare;   // 1:2 risk-reward

        assertThat(signal.stopLoss()).isCloseTo(expectedStopLoss, within(0.001));
        assertThat(signal.target()).isCloseTo(expectedTarget, within(0.001));
        assertThat(signal.entryPrice()).isCloseTo(close, within(0.001));
    }

    @Test
    void sellSignal_stopLossAndTargetCalculatedCorrectly() {
        double close = 100.0;
        double atr = 3.0;
        // RSI crossover below 70 for SELL
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close + 2, close - 2, close, close,
            72.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 1.0, atr, true
        );
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close + 2, close - 2, close, close,
            68.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close - 5, close - 3, true,
            900_000.0, 1.0, atr, true
        );

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        TradeSignal signal = result.get();
        double expectedStopLoss = close + 2 * atr;          // entry + 2×ATR
        double riskPerShare = expectedStopLoss - close;
        double expectedTarget = close - 2 * riskPerShare;   // 1:2 risk-reward

        assertThat(signal.stopLoss()).isCloseTo(expectedStopLoss, within(0.001));
        assertThat(signal.target()).isCloseTo(expectedTarget, within(0.001));
    }

    // ── Style routing ─────────────────────────────────────────────────────────

    @Test
    void timeframe1m_mapsToScalpStyle() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "1m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().style()).isEqualTo(TradingStyle.SCALP);
    }

    @Test
    void timeframe15m_mapsToSwingStyle() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);

        Optional<TradeSignal> result = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "15m", curr, prev, fundamentals());

        assertThat(result).isPresent();
        assertThat(result.get().style()).isEqualTo(TradingStyle.SWING);
    }

    // ── Signal ID ─────────────────────────────────────────────────────────────

    @Test
    void signalId_isDeterministic() {
        double close = 100.0;
        IndicatorSnapshot prev = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            28.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);
        IndicatorSnapshot curr = new IndicatorSnapshot(
            close - 2, close + 2, close, close,
            32.0, 0.0, 0.0, 0.0, 50.0, 50.0,
            close + 5, close + 3, false, 900_000.0, 1.0, 2.0, true);

        Optional<TradeSignal> r1 = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());
        Optional<TradeSignal> r2 = detector.evaluate(
            INSTRUMENT, SYMBOL, candle(close), "5m", curr, prev, fundamentals());

        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
        assertThat(r1.get().signalId()).isEqualTo(r2.get().signalId());
    }
}
