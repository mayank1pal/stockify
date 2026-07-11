package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.TickSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CandleBuilderTest {

    private static final long INSTRUMENT = 123456L;

    // 2024-01-15 09:15:00 UTC (maps to 14:45 IST — market open time in IST is 09:15)
    // Using a concrete base time in IST: 2024-01-15 09:15:00 IST = 2024-01-15 03:45:00 UTC
    private static final Instant BASE = Instant.parse("2024-01-15T03:45:00Z"); // 09:15 IST

    private CandleBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CandleBuilder(600);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private TickSnapshot tick(long token, Instant ts, double price, long vol) {
        return new TickSnapshot(token, price, price, price, price, price, vol,
                price, price, ts, ts);
    }

    private Instant minuteOffset(int minutes) {
        return BASE.plusSeconds(minutes * 60L);
    }

    private Instant secondOffset(int minutes, int seconds) {
        return BASE.plusSeconds(minutes * 60L + seconds);
    }

    // ── Test 1: First tick creates accumulator but no completed candle ────────

    @Test
    void firstTickProducesNoCompletedCandle() {
        Instant ts = minuteOffset(0);
        List<Candle> result = builder.onTick(tick(INSTRUMENT, ts, 100.0, 1000));

        assertThat(result).isEmpty();
        assertThat(builder.getCompletedCandles(INSTRUMENT, "1m")).isEmpty();
    }

    // ── Test 2: Tick in next minute closes the previous candle ───────────────

    @Test
    void tickInNextMinuteCLosesPreviousCandle() {
        builder.onTick(tick(INSTRUMENT, minuteOffset(0), 100.0, 1000));
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 105.0, 500));

        // Tick at minute 1 should close minute 0's candle
        List<Candle> closed = builder.onTick(tick(INSTRUMENT, minuteOffset(1), 110.0, 200));

        assertThat(closed).hasSize(1);
        Candle c = closed.get(0);
        assertThat(c.instrumentToken()).isEqualTo(INSTRUMENT);
        assertThat(c.openTime()).isEqualTo(minuteOffset(0));
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.close()).isEqualTo(105.0); // last tick price before candle closed
        assertThat(c.high()).isEqualTo(105.0);
        assertThat(c.low()).isEqualTo(100.0);
        assertThat(c.volume()).isEqualTo(1500L);

        // The new tick at minute 1 is now open but not closed
        assertThat(builder.getCompletedCandles(INSTRUMENT, "1m")).hasSize(1);
    }

    // ── Test 3: OHLC calculated correctly from multiple ticks ─────────────────

    @Test
    void ohlcCalculatedCorrectlyFromMultipleTicks() {
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 0), 100.0, 100));   // open
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 15), 120.0, 200));  // new high
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 90.0, 150));   // new low
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 45), 110.0, 50));   // close candidate

        // Close the candle with a tick in the next minute
        List<Candle> closed = builder.onTick(tick(INSTRUMENT, minuteOffset(1), 115.0, 300));

        assertThat(closed).hasSize(1);
        Candle c = closed.get(0);
        assertThat(c.open()).isEqualTo(100.0);
        assertThat(c.high()).isEqualTo(120.0);
        assertThat(c.low()).isEqualTo(90.0);
        assertThat(c.close()).isEqualTo(110.0); // last tick in the minute
        assertThat(c.volume()).isEqualTo(500L);
    }

    // ── Test 4: Empty minutes produce carry-forward candles ───────────────────

    @Test
    void emptyMinutesProduceCarryForwardCandles() {
        // Tick at minute 0
        builder.onTick(tick(INSTRUMENT, minuteOffset(0), 100.0, 1000));
        // Close it with tick at minute 0, 30s
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 105.0, 500));

        // Jump to minute 3 — minutes 1 and 2 are empty
        List<Candle> closed = builder.onTick(tick(INSTRUMENT, minuteOffset(3), 108.0, 300));

        // Should produce: candle for minute 0 (real), candle for minute 1 (carry), candle for minute 2 (carry)
        assertThat(closed).hasSize(3);

        Candle real = closed.get(0);
        assertThat(real.openTime()).isEqualTo(minuteOffset(0));
        assertThat(real.open()).isEqualTo(100.0);
        assertThat(real.close()).isEqualTo(105.0);
        assertThat(real.volume()).isEqualTo(1500L);

        // Minute 1: carry-forward
        Candle carry1 = closed.get(1);
        assertThat(carry1.openTime()).isEqualTo(minuteOffset(1));
        assertThat(carry1.open()).isEqualTo(105.0);   // previous close
        assertThat(carry1.high()).isEqualTo(105.0);
        assertThat(carry1.low()).isEqualTo(105.0);
        assertThat(carry1.close()).isEqualTo(105.0);
        assertThat(carry1.volume()).isEqualTo(0L);

        // Minute 2: carry-forward
        Candle carry2 = closed.get(2);
        assertThat(carry2.openTime()).isEqualTo(minuteOffset(2));
        assertThat(carry2.open()).isEqualTo(105.0);   // same carry price
        assertThat(carry2.volume()).isEqualTo(0L);
    }

    // ── Test 5: VWAP calculated correctly ─────────────────────────────────────

    @Test
    void vwapCalculatedCorrectly() {
        // Tick 1: price=100, vol=1000  → contribution: 100_000
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 0), 100.0, 1000));
        // Tick 2: price=200, vol=500   → contribution: 100_000
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 200.0, 500));
        // Total vol = 1500, total value = 200_000 → VWAP = 133.33...

        List<Candle> closed = builder.onTick(tick(INSTRUMENT, minuteOffset(1), 150.0, 100));

        assertThat(closed).hasSize(1);
        double expectedVwap = (100.0 * 1000 + 200.0 * 500) / (1000 + 500);
        assertThat(closed.get(0).vwap()).isCloseTo(expectedVwap, within(0.001));
    }

    // ── Test 6: Sliding window does not exceed maxSize candles ────────────────

    @Test
    void slidingWindowDoesNotExceedMaxSize() {
        CandleBuilder smallBuilder = new CandleBuilder(5); // tiny window

        // Feed 10 minutes of ticks
        for (int m = 0; m < 10; m++) {
            smallBuilder.onTick(tick(INSTRUMENT, minuteOffset(m), 100.0 + m, 100));
        }
        // Close the last open candle with a tick in minute 10
        smallBuilder.onTick(tick(INSTRUMENT, minuteOffset(10), 200.0, 100));

        List<Candle> candles = smallBuilder.getCompletedCandles(INSTRUMENT, "1m");
        assertThat(candles).hasSize(5); // capped at window size
    }

    // ── Test 7: 5m candles derived from 1m at correct boundaries ─────────────

    @Test
    void fiveMinuteCandlesDerivedAtCorrectBoundary() {
        // Feed ticks for minutes 0..4 to accumulate 5 × 1m candles
        // Minute boundaries: 0,1,2,3,4 — 5m candle closes when minute 4 completes
        for (int m = 0; m < 5; m++) {
            builder.onTick(tick(INSTRUMENT, minuteOffset(m), 100.0 + m, 100 * (m + 1)));
        }
        // Tick at minute 5 closes minute 4's 1m candle → triggers 5m candle
        builder.onTick(tick(INSTRUMENT, minuteOffset(5), 110.0, 500));

        List<Candle> fiveMin = builder.getCompletedCandles(INSTRUMENT, "5m");
        assertThat(fiveMin).hasSize(1);

        Candle c5 = fiveMin.get(0);
        assertThat(c5.openTime()).isEqualTo(minuteOffset(0)); // starts at minute 0
        assertThat(c5.open()).isEqualTo(100.0);               // open of first 1m candle
        assertThat(c5.high()).isEqualTo(104.0);               // max of 100..104
        assertThat(c5.low()).isEqualTo(100.0);                // min
        assertThat(c5.close()).isEqualTo(104.0);              // close of last 1m candle
        // Volume = 100+200+300+400+500 = 1500
        assertThat(c5.volume()).isEqualTo(1500L);
    }

    // ── Test 8: 5m candle not emitted before boundary ─────────────────────────

    @Test
    void fiveMinuteCandleNotEmittedBeforeBoundary() {
        // 4 complete 1m candles — not enough for a 5m candle
        for (int m = 0; m < 4; m++) {
            builder.onTick(tick(INSTRUMENT, minuteOffset(m), 100.0 + m, 100));
        }
        builder.onTick(tick(INSTRUMENT, minuteOffset(4), 108.0, 100));

        assertThat(builder.getCompletedCandles(INSTRUMENT, "5m")).isEmpty();
    }

    // ── Test 9: 15m candles derived at correct boundary ───────────────────────

    @Test
    void fifteenMinuteCandlesDerivedAtCorrectBoundary() {
        // Feed 15 minutes of ticks
        for (int m = 0; m < 15; m++) {
            builder.onTick(tick(INSTRUMENT, minuteOffset(m), 100.0 + m, 100));
        }
        // Tick at minute 15 closes minute 14's 1m candle → triggers 15m candle
        builder.onTick(tick(INSTRUMENT, minuteOffset(15), 120.0, 100));

        List<Candle> fifteenMin = builder.getCompletedCandles(INSTRUMENT, "15m");
        assertThat(fifteenMin).hasSize(1);

        Candle c15 = fifteenMin.get(0);
        assertThat(c15.openTime()).isEqualTo(minuteOffset(0));
        assertThat(c15.open()).isEqualTo(100.0);
        assertThat(c15.high()).isEqualTo(114.0);
        assertThat(c15.low()).isEqualTo(100.0);
        assertThat(c15.close()).isEqualTo(114.0);
        assertThat(c15.volume()).isEqualTo(1500L);
    }

    // ── Test 10: closeStaleCandles closes open accumulator ────────────────────

    @Test
    void closeStaleCandles_closesOpenAccumulator() {
        builder.onTick(tick(INSTRUMENT, minuteOffset(0), 100.0, 1000));
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 105.0, 500));

        // Heartbeat fires at minute 1 + 2s grace (simulated)
        Instant heartbeat = minuteOffset(1).plusSeconds(2);
        List<Candle> closed = builder.closeStaleCandles(heartbeat);

        assertThat(closed).hasSize(1);
        Candle c = closed.get(0);
        assertThat(c.openTime()).isEqualTo(minuteOffset(0));
        assertThat(c.close()).isEqualTo(105.0);
        assertThat(c.volume()).isEqualTo(1500L);
    }

    // ── Test 11: Multiple instruments are independent ─────────────────────────

    @Test
    void multipleInstrumentsAreIndependent() {
        long inst1 = 111L;
        long inst2 = 222L;

        builder.onTick(tick(inst1, minuteOffset(0), 100.0, 1000));
        builder.onTick(tick(inst2, minuteOffset(0), 200.0, 2000));

        // Close inst1's candle
        List<Candle> closed1 = builder.onTick(tick(inst1, minuteOffset(1), 105.0, 100));
        // Close inst2's candle
        List<Candle> closed2 = builder.onTick(tick(inst2, minuteOffset(1), 210.0, 100));

        assertThat(closed1).hasSize(1);
        assertThat(closed1.get(0).instrumentToken()).isEqualTo(inst1);
        assertThat(closed1.get(0).open()).isEqualTo(100.0);

        assertThat(closed2).hasSize(1);
        assertThat(closed2.get(0).instrumentToken()).isEqualTo(inst2);
        assertThat(closed2.get(0).open()).isEqualTo(200.0);
    }

    // ── Test 12: closeStaleCandles is a no-op when no open accumulator ────────

    @Test
    void closeStaleCandles_noOpWhenNoOpenAccumulator() {
        // Nothing fed yet
        List<Candle> closed = builder.closeStaleCandles(minuteOffset(1));
        assertThat(closed).isEmpty();
    }

    // ── Test 13: getCompletedCandles returns empty for unknown instrument ─────

    @Test
    void getCompletedCandlesEmptyForUnknownInstrument() {
        assertThat(builder.getCompletedCandles(999L, "1m")).isEmpty();
        assertThat(builder.getCompletedCandles(999L, "5m")).isEmpty();
        assertThat(builder.getCompletedCandles(999L, "15m")).isEmpty();
    }

    // ── Test 14: Carry-forward candle does not trigger 5m if no prior data ────

    @Test
    void carryForwardVwapEqualsClosePrice() {
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 0), 100.0, 1000));
        builder.onTick(tick(INSTRUMENT, secondOffset(0, 30), 100.0, 1000));
        // Jump 2 minutes — minute 1 is carry-forward
        List<Candle> closed = builder.onTick(tick(INSTRUMENT, minuteOffset(2), 100.0, 100));

        // Minute 1 carry-forward: VWAP should equal close price since vol=0
        Candle carry = closed.get(1);
        assertThat(carry.openTime()).isEqualTo(minuteOffset(1));
        assertThat(carry.volume()).isEqualTo(0L);
        assertThat(carry.vwap()).isEqualTo(carry.close());
    }
}
