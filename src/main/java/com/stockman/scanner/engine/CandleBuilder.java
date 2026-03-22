package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.TickSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates raw ticks into OHLCV candles at 1m, 5m, and 15m timeframes.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Single-writer per instrument — callers must ensure that all ticks for a given
 *       instrument are processed on the same thread (partitioned worker). No internal
 *       synchronisation is therefore needed.</li>
 *   <li>Event-time closing — a candle for minute N closes when the first tick whose
 *       {@code exchangeTimestamp} falls in minute N+1 (or later) arrives, or when the
 *       heartbeat scheduler calls {@link #closeStaleCandles(Instant)}.</li>
 *   <li>Carry-forward — if one or more minutes pass with no tick, synthetic candles
 *       (OHLC = previous close, volume = 0) are emitted for each missing minute.</li>
 *   <li>Sliding window — at most {@code windowSize} completed candles are kept per
 *       (instrument, timeframe) pair. Older candles are dropped from the front.</li>
 *   <li>5m / 15m candles — derived from completed 1m candles at minute boundaries
 *       where {@code minuteOfDay % 5 == 4} (5m) or {@code minuteOfDay % 15 == 14} (15m),
 *       counting from midnight UTC (consistent with exchange-time boundaries).</li>
 * </ul>
 */
@Slf4j
public class CandleBuilder {

    private final int windowSize;

    /** Per-instrument mutable accumulator for the currently-open 1m candle. */
    private final Map<Long, CandleAccumulator> accumulators = new HashMap<>();

    /** Completed candles per (instrument, timeframe). */
    private final Map<Long, Map<String, Deque<Candle>>> completed = new HashMap<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param windowSize maximum number of completed candles kept per (instrument, timeframe).
     */
    public CandleBuilder(int windowSize) {
        this.windowSize = windowSize;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Process a single tick.
     *
     * @param tick incoming market tick
     * @return list of 1m candles that were finalised as a result of this tick (usually 0 or 1,
     *         but may be more if multiple minutes were skipped)
     */
    public List<Candle> onTick(TickSnapshot tick) {
        Instant minuteBoundary = truncateToMinute(tick.exchangeTimestamp());
        CandleAccumulator acc = accumulators.get(tick.instrumentToken());

        List<Candle> newlyFinalized = new ArrayList<>();

        if (acc == null) {
            // First tick ever for this instrument — just open an accumulator.
            accumulators.put(tick.instrumentToken(), openAccumulator(tick, minuteBoundary));
            return newlyFinalized; // nothing finalized yet
        }

        if (minuteBoundary.equals(acc.openTime)) {
            // Same minute — update the running accumulator.
            updateAccumulator(acc, tick);
        } else {
            // New minute (or later) — finalize all stale candles, then start fresh.
            newlyFinalized.addAll(finalizeUntil(tick.instrumentToken(), acc, minuteBoundary));
            CandleAccumulator newAcc = openAccumulator(tick, minuteBoundary);
            accumulators.put(tick.instrumentToken(), newAcc);
        }

        return newlyFinalized;
    }

    /**
     * Return an immutable snapshot of completed candles for the given instrument and timeframe.
     *
     * @param instrumentToken instrument identifier
     * @param timeframe       "1m", "5m", or "15m"
     * @return list ordered oldest-first; empty if no data exists
     */
    public List<Candle> getCompletedCandles(long instrumentToken, String timeframe) {
        Map<String, Deque<Candle>> byTimeframe = completed.get(instrumentToken);
        if (byTimeframe == null) return List.of();
        Deque<Candle> window = byTimeframe.get(timeframe);
        if (window == null) return List.of();
        return List.copyOf(window);
    }

    /**
     * Called by the heartbeat scheduler (every minute + 2-second grace period).
     * Closes any accumulator whose open minute is strictly before {@code now}'s minute.
     *
     * @param now current wall-clock instant (should include the 2-second grace)
     * @return list of all candles that were closed across all instruments
     */
    public List<Candle> closeStaleCandles(Instant now) {
        Instant currentMinute = truncateToMinute(now);
        List<Candle> closed = new ArrayList<>();

        for (Map.Entry<Long, CandleAccumulator> entry : accumulators.entrySet()) {
            long token = entry.getKey();
            CandleAccumulator acc = entry.getValue();

            if (acc.openTime.isBefore(currentMinute)) {
                // Finalize up to (but not including) currentMinute
                List<Candle> finalized = finalizeUntil(token, acc, currentMinute);
                closed.addAll(finalized);
                // Replace the accumulator with a sentinel that records the last close time
                // but has no data yet — next real tick will open a fresh one properly.
                accumulators.put(token, openEmptyAccumulator(token, currentMinute, acc.close));
            }
        }

        return closed;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /** Truncate an instant to the minute boundary (floor). */
    private static Instant truncateToMinute(Instant ts) {
        return ts.truncatedTo(ChronoUnit.MINUTES);
    }

    /** Create a fresh accumulator pre-populated with the first tick. */
    private CandleAccumulator openAccumulator(TickSnapshot tick, Instant minuteBoundary) {
        CandleAccumulator acc = new CandleAccumulator();
        acc.instrumentToken = tick.instrumentToken();
        acc.openTime = minuteBoundary;
        acc.open = tick.ltp();
        acc.high = tick.ltp();
        acc.low = tick.ltp();
        acc.close = tick.ltp();
        acc.volume = tick.volume();
        acc.vwapNumerator = tick.ltp() * tick.volume();
        acc.vwapDenominator = tick.volume();
        acc.hasData = true;
        return acc;
    }

    /**
     * Create an empty accumulator (used by heartbeat to record position in time without data).
     * The close field is used as the carry-forward price.
     */
    private CandleAccumulator openEmptyAccumulator(long token, Instant minuteBoundary, double carryClose) {
        CandleAccumulator acc = new CandleAccumulator();
        acc.instrumentToken = token;
        acc.openTime = minuteBoundary;
        acc.open = carryClose;
        acc.high = carryClose;
        acc.low = carryClose;
        acc.close = carryClose;
        acc.volume = 0;
        acc.vwapNumerator = 0;
        acc.vwapDenominator = 0;
        acc.hasData = false;
        return acc;
    }

    /** Update an open accumulator with a new tick in the same minute. */
    private void updateAccumulator(CandleAccumulator acc, TickSnapshot tick) {
        if (tick.ltp() > acc.high) acc.high = tick.ltp();
        if (tick.ltp() < acc.low)  acc.low  = tick.ltp();
        acc.close = tick.ltp();
        acc.volume += tick.volume();
        acc.vwapNumerator   += tick.ltp() * tick.volume();
        acc.vwapDenominator += tick.volume();
        acc.hasData = true;
    }

    /**
     * Finalize the current accumulator and emit carry-forward candles for any minutes
     * between {@code acc.openTime} and {@code untilMinute} (exclusive).
     *
     * @param token       instrument token (used when storing to completed windows)
     * @param acc         the accumulator to finalize
     * @param untilMinute the first minute that should NOT be finalized (it is the new open)
     * @return ordered list of newly finalized candles (real candle first, then carry-forwards)
     */
    private List<Candle> finalizeUntil(long token, CandleAccumulator acc, Instant untilMinute) {
        List<Candle> result = new ArrayList<>();

        // 1. Finalize the current accumulator into a real candle (if it had data).
        Candle realCandle = buildCandle(acc);
        storeCandle(token, realCandle, "1m");
        result.add(realCandle);

        double carryClose = realCandle.close();

        // 2. Emit carry-forward candles for every missing minute between accOpenTime+1 and untilMinute-1.
        Instant fillMinute = acc.openTime.plus(1, ChronoUnit.MINUTES);
        while (fillMinute.isBefore(untilMinute)) {
            Candle carry = new Candle(token, fillMinute, carryClose, carryClose, carryClose, carryClose, 0L, carryClose);
            storeCandle(token, carry, "1m");
            result.add(carry);
            fillMinute = fillMinute.plus(1, ChronoUnit.MINUTES);
        }

        // 3. For each newly stored 1m candle, check if it triggers 5m/15m aggregation.
        for (Candle c : result) {
            maybeAggregate(token, c, 5, "5m");
            maybeAggregate(token, c, 15, "15m");
        }

        return result;
    }

    /** Convert a {@link CandleAccumulator} into an immutable {@link Candle}. */
    private Candle buildCandle(CandleAccumulator acc) {
        double vwap = acc.vwapDenominator > 0
                ? acc.vwapNumerator / acc.vwapDenominator
                : acc.close;
        return new Candle(acc.instrumentToken, acc.openTime,
                acc.open, acc.high, acc.low, acc.close,
                acc.volume, vwap);
    }

    /** Add a candle to the sliding window for the given timeframe. */
    private void storeCandle(long token, Candle candle, String timeframe) {
        completed
                .computeIfAbsent(token, k -> new HashMap<>())
                .computeIfAbsent(timeframe, k -> new ArrayDeque<>());

        Deque<Candle> window = completed.get(token).get(timeframe);
        window.addLast(candle);
        while (window.size() > windowSize) {
            window.pollFirst();
        }
    }

    /**
     * After a 1m candle is stored, check whether it falls on an aggregation boundary
     * (minute offset within the day, 0-indexed). If so, aggregate the last {@code period}
     * completed 1m candles into one higher-timeframe candle.
     *
     * <p>Boundary rule: the Nth multi-minute candle (1-indexed) closes when the
     * (N×period - 1)th 1m candle (0-indexed) completes. Equivalently, using the number
     * of seconds since epoch divided by 60 seconds: {@code (epochMinute % period) == (period - 1)}.
     *
     * @param token     instrument
     * @param oneMinute the freshly completed 1m candle
     * @param period    aggregation period (5 or 15)
     * @param timeframe label ("5m" or "15m")
     */
    private void maybeAggregate(long token, Candle oneMinute, int period, String timeframe) {
        long epochMinute = oneMinute.openTime().getEpochSecond() / 60;
        if (epochMinute % period != (period - 1)) return;

        // Grab the last `period` completed 1m candles
        Deque<Candle> oneMinWindow = completed.getOrDefault(token, Map.of())
                .getOrDefault("1m", new ArrayDeque<>());

        if (oneMinWindow.size() < period) return;

        // Take last `period` elements (tail of the deque)
        List<Candle> slice = new ArrayList<>(oneMinWindow).subList(
                oneMinWindow.size() - period, oneMinWindow.size());

        Candle aggregated = aggregateCandles(token, slice);
        storeCandle(token, aggregated, timeframe);
    }

    /** Merge a list of 1m candles into a single higher-timeframe candle. */
    private Candle aggregateCandles(long token, List<Candle> candles) {
        Instant openTime = candles.get(0).openTime();
        double open  = candles.get(0).open();
        double high  = candles.stream().mapToDouble(Candle::high).max().orElse(open);
        double low   = candles.stream().mapToDouble(Candle::low).min().orElse(open);
        double close = candles.get(candles.size() - 1).close();
        long   vol   = candles.stream().mapToLong(Candle::volume).sum();

        // VWAP of the aggregate: volume-weighted average of per-candle VWAPs
        double totalValue  = candles.stream().mapToDouble(c -> c.vwap() * c.volume()).sum();
        double totalVolume = vol;
        double vwap = totalVolume > 0 ? totalValue / totalVolume : close;

        return new Candle(token, openTime, open, high, low, close, vol, vwap);
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    /** Mutable state for the currently-open 1m candle of one instrument. */
    static class CandleAccumulator {
        long instrumentToken;
        Instant openTime;
        double open;
        double high;
        double low;
        double close;
        long volume;
        double vwapNumerator;
        double vwapDenominator;
        boolean hasData;
    }
}
