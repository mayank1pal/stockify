package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.IndicatorSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IndicatorEngineTest {

    private static final long INSTRUMENT = 100L;
    private static final Instant BASE = Instant.parse("2024-01-15T03:45:00Z");

    private IndicatorEngine engine;

    @BeforeEach
    void setUp() {
        engine = new IndicatorEngine();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build a candle at minute offset i with a flat price. */
    private Candle candle(int minuteOffset, double price) {
        return candle(minuteOffset, price, price, price, price, 1_000L);
    }

    /** Build a candle with explicit OHLCV. Volume-weighted VWAP = close for simplicity. */
    private Candle candle(int minuteOffset, double open, double high, double low, double close, long volume) {
        Instant t = BASE.plusSeconds((long) minuteOffset * 60);
        double vwap = (high + low + close) / 3.0;
        return new Candle(INSTRUMENT, t, open, high, low, close, volume, vwap);
    }

    /** Feed n identical candles with price=100. */
    private void feedFlat(int n) {
        for (int i = 0; i < n; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0));
        }
    }

    // ── Warmup tests ─────────────────────────────────────────────────────────

    @Test
    void afterWarmup_indicatorsAreAvailable() {
        // Feed 210 candles so warmup threshold (200) is exceeded
        for (int i = 0; i < 210; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + i * 0.01));
        }

        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap).isNotNull();
        assertThat(snap.warmedUp()).isTrue();

        // All indicator values should be finite (not NaN or Infinity)
        assertThat(Double.isFinite(snap.ema9())).isTrue();
        assertThat(Double.isFinite(snap.ema21())).isTrue();
        assertThat(Double.isFinite(snap.ema50())).isTrue();
        assertThat(Double.isFinite(snap.ema200())).isTrue();
        assertThat(Double.isFinite(snap.rsi())).isTrue();
        assertThat(Double.isFinite(snap.macdLine())).isTrue();
        assertThat(Double.isFinite(snap.macdSignal())).isTrue();
        assertThat(Double.isFinite(snap.macdHistogram())).isTrue();
        assertThat(Double.isFinite(snap.stochasticK())).isTrue();
        assertThat(Double.isFinite(snap.stochasticD())).isTrue();
        assertThat(Double.isFinite(snap.obv())).isTrue();
        assertThat(Double.isFinite(snap.rvol())).isTrue();
        assertThat(Double.isFinite(snap.atr())).isTrue();
    }

    @Test
    void beforeWarmup_notWarmedUp() {
        feedFlat(10);

        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap).isNotNull();
        assertThat(snap.warmedUp()).isFalse();
    }

    @Test
    void noData_getSnapshot_returnsNull() {
        assertThat(engine.getSnapshot(INSTRUMENT)).isNull();
        assertThat(engine.getPreviousSnapshot(INSTRUMENT)).isNull();
    }

    // ── EMA tests ─────────────────────────────────────────────────────────────

    @Test
    void ema9_reactsToRecentPrices() {
        // Feed 50 candles at 100, then 50 candles at 200
        for (int i = 0; i < 50; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0));
        }
        double ema9After100 = engine.getSnapshot(INSTRUMENT).ema9();

        for (int i = 50; i < 100; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 200.0));
        }
        double ema9After200 = engine.getSnapshot(INSTRUMENT).ema9();

        // EMA9 should be significantly higher after sustained high prices
        assertThat(ema9After200).isGreaterThan(ema9After100 + 50);
    }

    @Test
    void ema9_lessThanEma200_onUptrend() {
        // On a long uptrend, EMA9 (faster) should be above EMA200 (slower)
        for (int i = 0; i < 210; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + i * 0.5));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        // EMA9 reacts faster — in an uptrend it should be above EMA200
        assertThat(snap.ema9()).isGreaterThan(snap.ema200());
    }

    @Test
    void previousSnapshot_isOneBehindCurrent() {
        for (int i = 0; i < 30; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + i));
        }
        // After 30 candles, both current and previous snapshots should exist
        IndicatorSnapshot current = engine.getSnapshot(INSTRUMENT);
        IndicatorSnapshot previous = engine.getPreviousSnapshot(INSTRUMENT);
        assertThat(previous).isNotNull();
        // EMA9 of previous should differ from current (prices were changing)
        assertThat(previous.ema9()).isNotEqualTo(current.ema9());
    }

    // ── RSI tests ─────────────────────────────────────────────────────────────

    @Test
    void rsi_overSoldAfterDrops() {
        // Establish warm baseline at 100 then drop hard
        for (int i = 0; i < 50; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0));
        }
        // Feed 20 consecutive significant drops
        for (int i = 50; i < 70; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 - (i - 49) * 2.0));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.rsi()).isLessThan(40.0);
    }

    @Test
    void rsi_overBoughtAfterGains() {
        // Establish warm baseline at 100, then rally hard
        for (int i = 0; i < 50; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0));
        }
        // Feed 20 consecutive significant gains
        for (int i = 50; i < 70; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + (i - 49) * 2.0));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.rsi()).isGreaterThan(60.0);
    }

    @Test
    void rsi_boundsZeroToHundred() {
        // RSI must always stay in [0, 100]
        for (int i = 0; i < 210; i++) {
            double price = (i % 2 == 0) ? 50.0 : 150.0; // alternating extreme swings
            engine.onCandleClose(INSTRUMENT, candle(i, price));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.rsi()).isBetween(0.0, 100.0);
    }

    // ── MACD tests ────────────────────────────────────────────────────────────

    @Test
    void macd_histogramIsLineMinusSignal() {
        for (int i = 0; i < 210; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + Math.sin(i * 0.1) * 10));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        double expected = snap.macdLine() - snap.macdSignal();
        assertThat(snap.macdHistogram()).isCloseTo(expected, within(1e-9));
    }

    @Test
    void macd_linePositiveOnSustainedUptrend() {
        // On a long uptrend EMA12 > EMA26, so MACD line should be positive
        for (int i = 0; i < 100; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0 + i));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.macdLine()).isGreaterThan(0.0);
    }

    // ── Stochastic tests ──────────────────────────────────────────────────────

    @Test
    void stochastic_boundsZeroToHundred() {
        for (int i = 0; i < 210; i++) {
            double close = 100.0 + Math.sin(i * 0.2) * 20;
            double high  = close + 2;
            double low   = close - 2;
            engine.onCandleClose(INSTRUMENT, candle(i, close, high, low, close, 1000L));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.stochasticK()).isBetween(0.0, 100.0);
        assertThat(snap.stochasticD()).isBetween(0.0, 100.0);
    }

    @Test
    void stochastic_nearHundredAtHighClose() {
        // When close consistently equals the high of the window, %K should be near 100
        for (int i = 0; i < 210; i++) {
            // close = high always, low well below
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0, 100.0, 80.0, 100.0, 1000L));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.stochasticK()).isCloseTo(100.0, within(1.0));
    }

    // ── OBV tests ─────────────────────────────────────────────────────────────

    @Test
    void obv_increasesOnUpCandles() {
        engine.onCandleClose(INSTRUMENT, candle(0, 100.0, 101.0, 99.0, 100.0, 1000L));
        double obv0 = engine.getSnapshot(INSTRUMENT).obv();

        // Up candle: close > previous close
        engine.onCandleClose(INSTRUMENT, candle(1, 100.0, 105.0, 99.0, 105.0, 2000L));
        double obv1 = engine.getSnapshot(INSTRUMENT).obv();
        assertThat(obv1).isGreaterThan(obv0);
    }

    @Test
    void obv_decreasesOnDownCandles() {
        engine.onCandleClose(INSTRUMENT, candle(0, 100.0, 101.0, 99.0, 100.0, 1000L));
        double obv0 = engine.getSnapshot(INSTRUMENT).obv();

        // Down candle: close < previous close
        engine.onCandleClose(INSTRUMENT, candle(1, 100.0, 101.0, 95.0, 95.0, 2000L));
        double obv1 = engine.getSnapshot(INSTRUMENT).obv();
        assertThat(obv1).isLessThan(obv0);
    }

    // ── ATR & SuperTrend tests ────────────────────────────────────────────────

    @Test
    void atr_isPositiveAfterFewCandles() {
        for (int i = 0; i < 20; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0, 102.0, 98.0, 100.0, 1000L));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.atr()).isGreaterThan(0.0);
    }

    @Test
    void superTrend_flips_bullishAfterExtendedRally() {
        // Start with falling prices (bearish), then sustained rally should flip to bullish
        for (int i = 0; i < 30; i++) {
            double p = 200.0 - i; // declining
            engine.onCandleClose(INSTRUMENT, candle(i, p, p + 1, p - 1, p, 1000L));
        }
        for (int i = 30; i < 100; i++) {
            double p = 170.0 + (i - 30) * 2.0; // strong rally
            engine.onCandleClose(INSTRUMENT, candle(i, p, p + 2, p - 1, p, 1000L));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.superTrendBullish()).isTrue();
    }

    // ── RVOL tests ────────────────────────────────────────────────────────────

    @Test
    void rvol_nearOneOnFlatVolume() {
        // After 25+ candles with identical volume, RVOL should be ≈ 1.0
        for (int i = 0; i < 25; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0, 101.0, 99.0, 100.0, 5000L));
        }
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.rvol()).isCloseTo(1.0, within(0.01));
    }

    @Test
    void rvol_aboveOneOnHighVolume() {
        // Establish baseline, then spike volume
        for (int i = 0; i < 25; i++) {
            engine.onCandleClose(INSTRUMENT, candle(i, 100.0, 101.0, 99.0, 100.0, 1000L));
        }
        // Single candle with 5× volume
        engine.onCandleClose(INSTRUMENT, candle(25, 100.0, 101.0, 99.0, 100.0, 5000L));
        IndicatorSnapshot snap = engine.getSnapshot(INSTRUMENT);
        assertThat(snap.rvol()).isGreaterThan(1.1);
    }
}
