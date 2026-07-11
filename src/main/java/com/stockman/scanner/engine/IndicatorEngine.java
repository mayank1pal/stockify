package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.IndicatorSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes 10 technical indicators incrementally on each candle close.
 *
 * <p>All indicators are O(1) per candle update (amortized for Stochastic).
 *
 * <p>Design notes:
 * <ul>
 *   <li>Single-writer per instrument — callers must ensure all candles for a given
 *       instrument are processed on the same thread (partitioned worker).</li>
 *   <li>Warm-up: {@code warmedUp = candleCount >= 200} (needed for EMA200).</li>
 *   <li>Previous snapshot: saved before each update for crossover detection.</li>
 * </ul>
 *
 * <h3>Indicators computed</h3>
 * <ul>
 *   <li>EMA(9, 21, 50, 200) — Wilder exponential smoothing</li>
 *   <li>SuperTrend — ATR-based dynamic support/resistance with trend flip</li>
 *   <li>VWAP — passed through from the Candle record</li>
 *   <li>RSI(14) — Wilder smoothed average gain/loss</li>
 *   <li>MACD(12,26,9) — EMA12−EMA26, signal line, histogram</li>
 *   <li>Stochastic(14,3,3) — monotonic-deque rolling high/low, SMA3 for %D</li>
 *   <li>RVOL — current volume / 20-period SMA of volume</li>
 *   <li>OBV — cumulative on-balance volume</li>
 *   <li>ATR(14) — Wilder smoothed true range (also used by SuperTrend)</li>
 * </ul>
 */
@Slf4j
@Component
public class IndicatorEngine {

    // SuperTrend configuration
    private static final int   SUPERTREND_ATR_PERIOD = 10;
    private static final double SUPERTREND_MULTIPLIER = 3.0;

    // Per-instrument mutable state, keyed by instrumentToken
    private final Map<Long, IndicatorState> states = new HashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process a closed candle and update all indicators for the instrument.
     *
     * @param instrumentToken instrument identifier
     * @param candle          the completed candle
     */
    public void onCandleClose(long instrumentToken, Candle candle) {
        IndicatorState state = states.computeIfAbsent(instrumentToken, k -> new IndicatorState());
        updateState(state, candle);
    }

    /**
     * Return the latest {@link IndicatorSnapshot} for the instrument, or {@code null} if
     * no candles have been processed yet.
     */
    public IndicatorSnapshot getSnapshot(long instrumentToken) {
        IndicatorState state = states.get(instrumentToken);
        return state == null ? null : state.current;
    }

    /**
     * Return the snapshot from the candle before the latest one, or {@code null} if fewer
     * than two candles have been processed. Used for crossover detection.
     */
    public IndicatorSnapshot getPreviousSnapshot(long instrumentToken) {
        IndicatorState state = states.get(instrumentToken);
        return state == null ? null : state.previous;
    }

    // ── Update orchestration ─────────────────────────────────────────────────

    private void updateState(IndicatorState s, Candle candle) {
        // Save current snapshot as previous before overwriting
        s.previous = s.current;

        s.candleCount++;
        double close    = candle.close();
        double high     = candle.high();
        double low      = candle.low();
        long   volume   = candle.volume();
        // Capture prevClose BEFORE any indicator mutates s.prevClose
        double prevClose = s.prevClose;

        // ── ATR (must come before SuperTrend; also sets s.prevClose) ─────────
        updateAtr(s, candle, prevClose);

        // ── EMA ───────────────────────────────────────────────────────────────
        s.ema9   = updateEma(s.ema9,   close, 9);
        s.ema21  = updateEma(s.ema21,  close, 21);
        s.ema50  = updateEma(s.ema50,  close, 50);
        s.ema200 = updateEma(s.ema200, close, 200);

        // ── RSI ───────────────────────────────────────────────────────────────
        updateRsi(s, close);

        // ── MACD ──────────────────────────────────────────────────────────────
        updateMacd(s, close);

        // ── Stochastic ────────────────────────────────────────────────────────
        updateStochastic(s, high, low, close);

        // ── SuperTrend ────────────────────────────────────────────────────────
        updateSuperTrend(s, high, low, close, prevClose);

        // ── OBV ───────────────────────────────────────────────────────────────
        updateObv(s, close, volume, prevClose);

        // ── RVOL ──────────────────────────────────────────────────────────────
        updateRvol(s, volume);

        // ── Build snapshot ────────────────────────────────────────────────────
        boolean warmedUp = s.candleCount >= 200;
        s.current = new IndicatorSnapshot(
            s.ema9, s.ema21, s.ema50, s.ema200,
            s.rsi,
            s.macdLine, s.macdSignal, s.macdLine - s.macdSignal,
            s.stochasticK, s.stochasticD,
            candle.vwap(),
            s.superTrendValue, s.superTrendBullish,
            s.obv,
            s.rvol,
            s.atr,
            warmedUp
        );
    }

    // ── EMA ───────────────────────────────────────────────────────────────────

    /**
     * Wilder exponential moving average.
     * On the very first candle (prevEma == 0) we seed with the price itself.
     */
    private double updateEma(double prevEma, double price, int period) {
        if (prevEma == 0.0) {
            return price;
        }
        double k = 2.0 / (period + 1.0);
        return (price - prevEma) * k + prevEma;
    }

    // ── ATR ───────────────────────────────────────────────────────────────────

    private void updateAtr(IndicatorState s, Candle candle, double prevClose) {
        double high = candle.high();
        double low  = candle.low();

        double tr;
        if (s.candleCount == 1) {
            // Very first candle — no previous close available
            tr = high - low;
        } else {
            tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
        }

        if (s.atr == 0.0) {
            s.atr = tr;
        } else {
            // Wilder smoothing with period 14
            s.atr = (tr - s.atr) * (1.0 / 14.0) + s.atr;
        }

        // Update prevClose for the NEXT candle
        s.prevClose = candle.close();
    }

    // ── RSI ───────────────────────────────────────────────────────────────────

    private void updateRsi(IndicatorState s, double close) {
        if (s.rsiPrevClose == 0.0) {
            s.rsiPrevClose = close;
            s.rsi = 50.0; // neutral seed
            return;
        }

        double change = close - s.rsiPrevClose;
        s.rsiPrevClose = close;

        double gain = Math.max(change, 0.0);
        double loss = Math.max(-change, 0.0);

        if (s.rsiAvgGain == 0.0 && s.rsiAvgLoss == 0.0) {
            // Seed on very first delta
            s.rsiAvgGain = gain;
            s.rsiAvgLoss = loss;
        } else {
            // Wilder smoothing
            s.rsiAvgGain = (gain - s.rsiAvgGain) * (1.0 / 14.0) + s.rsiAvgGain;
            s.rsiAvgLoss = (loss - s.rsiAvgLoss) * (1.0 / 14.0) + s.rsiAvgLoss;
        }

        if (s.rsiAvgLoss == 0.0) {
            s.rsi = 100.0;
        } else {
            double rs = s.rsiAvgGain / s.rsiAvgLoss;
            s.rsi = 100.0 - (100.0 / (1.0 + rs));
        }
    }

    // ── MACD ──────────────────────────────────────────────────────────────────

    private void updateMacd(IndicatorState s, double close) {
        s.macdEma12 = updateEma(s.macdEma12, close, 12);
        s.macdEma26 = updateEma(s.macdEma26, close, 26);
        s.macdLine  = s.macdEma12 - s.macdEma26;
        s.macdSignal = updateEma(s.macdSignal, s.macdLine, 9);
    }

    // ── Stochastic ────────────────────────────────────────────────────────────

    /**
     * O(1) amortized via monotonic deques for highest-high and lowest-low over the
     * 14-period window. %D is a 3-period SMA of %K.
     */
    private void updateStochastic(IndicatorState s, double high, double low, double close) {
        int period = 14;

        // Update monotonic deques — each stores indices; we use candleCount as index
        long idx = s.candleCount - 1; // 0-based after increment

        // Max deque for high — back is greatest index, front might be out of window
        while (!s.stochHighDeque.isEmpty() && s.stochHighValues[(int) (s.stochHighDeque.peekLast() % period)] <= high) {
            s.stochHighDeque.pollLast();
        }
        s.stochHighDeque.addLast(idx);
        s.stochHighValues[(int) (idx % period)] = high;

        // Min deque for low
        while (!s.stochLowDeque.isEmpty() && s.stochLowValues[(int) (s.stochLowDeque.peekLast() % period)] >= low) {
            s.stochLowDeque.pollLast();
        }
        s.stochLowDeque.addLast(idx);
        s.stochLowValues[(int) (idx % period)] = low;

        // Evict out-of-window entries from the front
        while (!s.stochHighDeque.isEmpty() && s.stochHighDeque.peekFirst() <= idx - period) {
            s.stochHighDeque.pollFirst();
        }
        while (!s.stochLowDeque.isEmpty() && s.stochLowDeque.peekFirst() <= idx - period) {
            s.stochLowDeque.pollFirst();
        }

        // Compute %K
        double hh = s.stochHighValues[(int) (s.stochHighDeque.peekFirst() % period)];
        double ll = s.stochLowValues[(int)  (s.stochLowDeque.peekFirst()  % period)];
        double range = hh - ll;
        double kRaw = (range == 0.0) ? 50.0 : ((close - ll) / range) * 100.0;
        kRaw = Math.max(0.0, Math.min(100.0, kRaw));
        s.stochasticK = kRaw;

        // %D = 3-period SMA of %K via a small circular buffer
        s.stochKBuffer[(int) (idx % 3)] = kRaw;
        int filled = (int) Math.min(s.candleCount, 3);
        double dSum = 0;
        for (int i = 0; i < filled; i++) {
            dSum += s.stochKBuffer[i];
        }
        s.stochasticD = dSum / filled;
    }

    // ── SuperTrend ────────────────────────────────────────────────────────────

    /**
     * ATR-based SuperTrend with Wilder-smoothed ATR over {@value #SUPERTREND_ATR_PERIOD} periods.
     *
     * <p>Upper band = HL2 + multiplier × ATR; lower band = HL2 − multiplier × ATR.
     * Direction flips when price breaches the active band.
     */
    private void updateSuperTrend(IndicatorState s, double high, double low, double close, double prevClose) {
        double hl2 = (high + low) / 2.0;

        // Compute ST-specific ATR via Wilder smoothing over SUPERTREND_ATR_PERIOD
        double trForSt;
        if (s.candleCount == 1) {
            trForSt = high - low;
            s.stAtr = trForSt;
        } else {
            trForSt = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            s.stAtr = (trForSt - s.stAtr) * (1.0 / SUPERTREND_ATR_PERIOD) + s.stAtr;
        }

        double basicUpper = hl2 + SUPERTREND_MULTIPLIER * s.stAtr;
        double basicLower = hl2 - SUPERTREND_MULTIPLIER * s.stAtr;

        // Adjust bands so they never widen in the current trend direction
        double finalUpper = (basicUpper < s.stFinalUpper || prevClose > s.stFinalUpper)
                ? basicUpper : s.stFinalUpper;
        double finalLower = (basicLower > s.stFinalLower || prevClose < s.stFinalLower)
                ? basicLower : s.stFinalLower;

        s.stFinalUpper = finalUpper;
        s.stFinalLower = finalLower;

        // Direction: if previous close was above upper band it was bearish, flip on breach
        if (s.candleCount == 1) {
            // Seed: assume bullish if close > lower band
            s.superTrendBullish = close > finalLower;
            s.superTrendValue   = s.superTrendBullish ? finalLower : finalUpper;
        } else {
            if (!s.superTrendBullish && close > s.stFinalUpper) {
                s.superTrendBullish = true;
            } else if (s.superTrendBullish && close < s.stFinalLower) {
                s.superTrendBullish = false;
            }
            s.superTrendValue = s.superTrendBullish ? finalLower : finalUpper;
        }
    }

    // ── OBV ───────────────────────────────────────────────────────────────────

    private void updateObv(IndicatorState s, double close, long volume, double prevClose) {
        if (s.candleCount == 1) {
            // First candle — just add volume as initial OBV
            s.obv += volume;
        } else if (close > prevClose) {
            s.obv += volume;
        } else if (close < prevClose) {
            s.obv -= volume;
            // unchanged close → OBV stays the same
        }
    }

    // ── RVOL ─────────────────────────────────────────────────────────────────

    private void updateRvol(IndicatorState s, long volume) {
        int rvolPeriod = 20;

        // Maintain a circular buffer of the last 20 volumes for O(1) SMA update
        int slot = (int) ((s.candleCount - 1) % rvolPeriod);
        s.volumeBuffer[slot] = volume;

        int filled = (int) Math.min(s.candleCount, rvolPeriod);
        long sum = 0;
        for (int i = 0; i < filled; i++) {
            sum += s.volumeBuffer[i];
        }
        double avgVolume = (double) sum / filled;
        s.rvol = avgVolume == 0.0 ? 1.0 : (double) volume / avgVolume;
    }

    // ── Internal State ────────────────────────────────────────────────────────

    /**
     * All mutable running values for a single instrument.
     * Fields are package-private for testability; not intended to be accessed externally.
     */
    static class IndicatorState {

        // Snapshots
        IndicatorSnapshot current  = null;
        IndicatorSnapshot previous = null;

        // Candle count (1-based after first candle)
        long candleCount = 0;

        // Shared previous close (used by ATR, OBV, SuperTrend)
        double prevClose = 0.0;

        // EMA values
        double ema9   = 0.0;
        double ema21  = 0.0;
        double ema50  = 0.0;
        double ema200 = 0.0;

        // ATR (period 14, used by main ATR indicator)
        double atr = 0.0;

        // RSI
        double rsiPrevClose = 0.0;
        double rsiAvgGain   = 0.0;
        double rsiAvgLoss   = 0.0;
        double rsi          = 50.0;

        // MACD
        double macdEma12  = 0.0;
        double macdEma26  = 0.0;
        double macdLine   = 0.0;
        double macdSignal = 0.0;

        // Stochastic
        double stochasticK = 50.0;
        double stochasticD = 50.0;
        // Circular array of high/low values indexed by (candleIndex % 14)
        final double[] stochHighValues = new double[14];
        final double[] stochLowValues  = new double[14];
        // Monotonic deques storing candle indices (long)
        final Deque<Long> stochHighDeque = new ArrayDeque<>();
        final Deque<Long> stochLowDeque  = new ArrayDeque<>();
        // Circular buffer of %K values for 3-period SMA → %D
        final double[] stochKBuffer = new double[3];

        // SuperTrend
        double  stAtr           = 0.0;
        double  stFinalUpper    = Double.MAX_VALUE;
        double  stFinalLower    = 0.0;
        double  superTrendValue  = 0.0;
        boolean superTrendBullish = true;

        // OBV
        double obv = 0.0;

        // RVOL — circular buffer of last 20 volumes
        final long[] volumeBuffer = new long[20];
        double rvol = 1.0;
    }
}
