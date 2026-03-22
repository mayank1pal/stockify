package com.stockman.scanner.engine;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.event.SignalEvent;
import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.*;
import com.stockman.scanner.model.TickSnapshot;
import com.stockman.scanner.model.TradeSignal;
import com.stockman.scanner.service.InstrumentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ScannerPipeline}.
 *
 * <p>Strategy: mock all collaborators to control signal flow precisely.
 * Real CandleBuilder / IndicatorEngine / SignalDetector have their own unit tests.
 */
@ExtendWith(MockitoExtension.class)
class ScannerPipelineTest {

    // ── Constants ──────────────────────────────────────────────────────────────

    private static final long TOKEN  = 738561L;
    private static final String SYMBOL = "INFY";

    /**
     * A tick timestamp that is AFTER the silence period (09:16:30 IST) AND after the
     * warmup window (09:15 + 30 min = 09:45 IST).
     * 2026-03-22 10:00:00 IST = 2026-03-22 04:30:00 UTC
     */
    private static final Instant NORMAL_TICK_TS = Instant.parse("2026-03-22T04:30:00Z");

    /**
     * A tick during the silence period: 09:15:00 IST = 03:45:00 UTC.
     */
    private static final Instant SILENCE_TICK_TS = Instant.parse("2026-03-22T03:45:00Z");

    /**
     * A tick during warmup (09:20 IST = 03:50 UTC) — after silence end but before warmup end.
     */
    private static final Instant WARMUP_TICK_TS = Instant.parse("2026-03-22T03:50:00Z");

    // ── Mocks ──────────────────────────────────────────────────────────────────

    @Mock private CandleBuilder candleBuilder;
    @Mock private IndicatorEngine indicatorEngine;
    @Mock private SignalDetector signalDetector;
    @Mock private SignalCooldownManager cooldownManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private InstrumentRegistry instrumentRegistry;

    // ── Subject under test ─────────────────────────────────────────────────────

    private ScannerPipeline pipeline;
    private ScannerConfig config;

    @BeforeEach
    void setUp() {
        config = new ScannerConfig();
        // Defaults: silencePeriodEnd=09:16:30, warmupMinutes=30
        // NORMAL_TICK_TS (10:00 IST) is past both gates.

        pipeline = new ScannerPipeline(
                candleBuilder, indicatorEngine, signalDetector,
                cooldownManager, eventPublisher, config, instrumentRegistry
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private TickSnapshot tick(Instant ts) {
        return new TickSnapshot(TOKEN, 100.0, 99.0, 101.0, 98.0, 99.5, 5000L,
                99.9, 100.1, ts, ts);
    }

    private Candle candle(Instant openTime) {
        return new Candle(TOKEN, openTime, 99.0, 101.0, 98.0, 100.0, 5000L, 100.0);
    }

    /** A warmed-up IndicatorSnapshot where no buy/sell rules fire (neutral state). */
    private IndicatorSnapshot neutralSnapshot() {
        double p = 100.0;
        return new IndicatorSnapshot(
                p - 2, p + 2, p, p, // ema9<ema21 (no cross)
                50.0,               // rsi neutral
                0.0, 0.0, 0.0,      // macd
                50.0, 50.0,         // stochastic
                p + 5,              // vwap > price → bearish lean but OBV flat
                p + 3, false,       // superTrend bearish (no flip)
                1_000_000.0,        // obv
                1.0,                // rvol (no spike)
                2.0,                // atr
                true                // warmedUp
        );
    }

    /** A warmed-up BUY snapshot: price > vwap, OBV rising, SuperTrend flipping bullish. */
    private IndicatorSnapshot buySnapshot() {
        double p = 100.0;
        return new IndicatorSnapshot(
                p + 1, p - 1, p, p, // ema9 > ema21 (just crossed above)
                50.0,
                0.0, 0.0, 0.0,
                50.0, 50.0,
                p - 5,              // vwap < price → bullish
                p - 3, true,        // superTrend bullish
                1_100_000.0,        // obv higher
                3.0,                // rvol spike
                2.0,
                true
        );
    }

    /** Previous snapshot where ema9 < ema21 (precondition for EMA cross rule). */
    private IndicatorSnapshot prevForBuyCross() {
        double p = 100.0;
        return new IndicatorSnapshot(
                p - 1, p + 1, p, p, // ema9 < ema21 before cross
                50.0,
                0.0, 0.0, 0.0,
                50.0, 50.0,
                p - 5,
                p - 3, false,       // was bearish before flip
                1_000_000.0,
                3.0,
                2.0,
                true
        );
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    // ── 1. Unknown token ───────────────────────────────────────────────────────

    @Test
    void unknownToken_skipsProcessing() {
        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(null);

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verifyNoInteractions(candleBuilder, indicatorEngine, signalDetector, eventPublisher);
    }

    // ── 2. No candles finalized (same minute) ─────────────────────────────────

    @Test
    void noFinalizedCandles_skipsIndicatorAndSignal() {
        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of());

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verifyNoInteractions(indicatorEngine, signalDetector, eventPublisher);
    }

    // ── 3. Happy path: signal detected and published ──────────────────────────

    @Test
    void signalDetected_publishesSignalEvent() {
        Instant candleOpen = NORMAL_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = buySnapshot();
        IndicatorSnapshot previous = prevForBuyCross();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        TradeSignal signal = new TradeSignal(
                "INFY:SCALP:1m:123:BUY", SYMBOL, TOKEN,
                SignalType.BUY, SignalStrength.STRONG, TradingStyle.SCALP,
                100.0, 96.0, 108.0,
                List.of("EMA9 crossed above EMA21 with volume spike (rvol=3.00)", "SuperTrend flipped bullish"),
                current, Instant.now());

        when(signalDetector.evaluate(eq(TOKEN), eq(SYMBOL), eq(finalized),
                eq("1m"), eq(current), eq(previous), isNull()))
                .thenReturn(Optional.of(signal));

        when(cooldownManager.canEmitSignal(SYMBOL, TradingStyle.SCALP, "1m",
                SignalType.BUY, SignalStrength.STRONG)).thenReturn(true);

        pipeline.processTick(tick(NORMAL_TICK_TS));

        // Verify cooldown was recorded
        verify(cooldownManager).recordSignal(SYMBOL, TradingStyle.SCALP, "1m",
                SignalType.BUY, SignalStrength.STRONG);

        // Capture and verify published event
        ArgumentCaptor<SignalEvent> captor = ArgumentCaptor.forClass(SignalEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        SignalEvent published = captor.getValue();
        assertThat(published.getSignal()).isSameAs(signal);
        assertThat(published.isAiPending()).isTrue(); // config default aiEnrichment.enabled=true
    }

    // ── 4. Silence period suppresses signals ──────────────────────────────────

    @Test
    void silencePeriod_suppressesSignal() {
        Instant candleOpen = SILENCE_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = buySnapshot();
        IndicatorSnapshot previous = prevForBuyCross();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        // Signal detector should never be called (gated before it)
        pipeline.processTick(tick(SILENCE_TICK_TS));

        verifyNoInteractions(signalDetector, cooldownManager, eventPublisher);
    }

    // ── 5. Warm-up period suppresses signals ──────────────────────────────────

    @Test
    void warmupPeriod_suppressesSignal() {
        // WARMUP_TICK_TS = 09:20 IST — past silence (09:16:30) but within warmup (09:15+30=09:45)
        Instant candleOpen = WARMUP_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = buySnapshot();
        IndicatorSnapshot previous = prevForBuyCross();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        pipeline.processTick(tick(WARMUP_TICK_TS));

        verifyNoInteractions(signalDetector, cooldownManager, eventPublisher);
    }

    // ── 6. Cooldown blocks duplicate signal ───────────────────────────────────

    @Test
    void cooldown_blocksDuplicateSignal() {
        Instant candleOpen = NORMAL_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = buySnapshot();
        IndicatorSnapshot previous = prevForBuyCross();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        TradeSignal signal = new TradeSignal(
                "INFY:SCALP:1m:123:BUY", SYMBOL, TOKEN,
                SignalType.BUY, SignalStrength.STRONG, TradingStyle.SCALP,
                100.0, 96.0, 108.0,
                List.of("reason"),
                current, Instant.now());

        when(signalDetector.evaluate(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(signal));

        // Cooldown says NO
        when(cooldownManager.canEmitSignal(any(), any(), any(), any(), any()))
                .thenReturn(false);

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verify(cooldownManager, never()).recordSignal(any(), any(), any(), any(), any());
        verifyNoInteractions(eventPublisher);
    }

    // ── 7. No signal from detector → no event published ───────────────────────

    @Test
    void noSignal_noEventPublished() {
        Instant candleOpen = NORMAL_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = neutralSnapshot();
        IndicatorSnapshot previous = neutralSnapshot();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        when(signalDetector.evaluate(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verifyNoInteractions(cooldownManager, eventPublisher);
    }

    // ── 8. Indicators not warmed up → no signal evaluation ───────────────────

    @Test
    void indicatorsNotWarmedUp_skipsSignalDetection() {
        Instant candleOpen = NORMAL_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        // Not warmed up
        IndicatorSnapshot coldSnap = new IndicatorSnapshot(
                1, 2, 3, 4, 50, 0, 0, 0, 50, 50, 100, 95, false, 1e6, 1, 2, false);
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(coldSnap);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(coldSnap);

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verifyNoInteractions(signalDetector, cooldownManager, eventPublisher);
    }

    // ── 9. Missing previous snapshot → no signal evaluation ──────────────────

    @Test
    void missingPreviousSnapshot_skipsSignalDetection() {
        Instant candleOpen = NORMAL_TICK_TS.minusSeconds(60);
        Candle finalized = candle(candleOpen);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);
        when(candleBuilder.onTick(any())).thenReturn(List.of(finalized));
        when(candleBuilder.getCompletedCandles(TOKEN, "5m")).thenReturn(List.of());
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(buySnapshot());
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(null); // first candle

        pipeline.processTick(tick(NORMAL_TICK_TS));

        verifyNoInteractions(signalDetector, cooldownManager, eventPublisher);
    }

    // ── 10. Silence period detection unit tests ───────────────────────────────

    @Test
    void isInSilencePeriod_beforeSilenceEnd_returnsTrue() {
        // 09:15:00 IST = 03:45:00 UTC
        Instant before = Instant.parse("2026-03-22T03:45:00Z");
        assertThat(pipeline.isInSilencePeriod(before)).isTrue();
    }

    @Test
    void isInSilencePeriod_afterSilenceEnd_returnsFalse() {
        // 09:17:00 IST = 03:47:00 UTC
        Instant after = Instant.parse("2026-03-22T03:47:00Z");
        assertThat(pipeline.isInSilencePeriod(after)).isFalse();
    }

    @Test
    void isInSilencePeriod_exactSilenceEnd_returnsFalse() {
        // 09:16:30 IST = 03:46:30 UTC
        Instant exact = Instant.parse("2026-03-22T03:46:30Z");
        assertThat(pipeline.isInSilencePeriod(exact)).isFalse();
    }

    // ── 11. Warm-up period detection unit tests ───────────────────────────────

    @Test
    void isInWarmupPeriod_duringWarmup_returnsTrue() {
        // 09:20:00 IST = 03:50:00 UTC (within 30-min warmup)
        Instant duringWarmup = Instant.parse("2026-03-22T03:50:00Z");
        assertThat(pipeline.isInWarmupPeriod(duringWarmup)).isTrue();
    }

    @Test
    void isInWarmupPeriod_afterWarmup_returnsFalse() {
        // 10:00:00 IST = 04:30:00 UTC
        Instant afterWarmup = Instant.parse("2026-03-22T04:30:00Z");
        assertThat(pipeline.isInWarmupPeriod(afterWarmup)).isFalse();
    }

    @Test
    void isInWarmupPeriod_beforeMarketOpen_returnsFalse() {
        // 09:00:00 IST = 03:30:00 UTC
        Instant beforeOpen = Instant.parse("2026-03-22T03:30:00Z");
        assertThat(pipeline.isInWarmupPeriod(beforeOpen)).isFalse();
    }

    // ── 12. HTF candle processed exactly once ─────────────────────────────────

    @Test
    void htfCandle_processedExactlyOnce_acrossMultipleTicks() {
        Instant candleOpen5m = NORMAL_TICK_TS.minusSeconds(300);
        Candle htfCandle = candle(candleOpen5m);

        // 1m candles returned on two consecutive ticks (different 1m boundaries)
        Candle first1m  = candle(NORMAL_TICK_TS.minusSeconds(60));
        Candle second1m = candle(NORMAL_TICK_TS);

        when(instrumentRegistry.getSymbol(TOKEN)).thenReturn(SYMBOL);

        // First tick
        when(candleBuilder.onTick(any()))
                .thenReturn(List.of(first1m))
                .thenReturn(List.of(second1m));

        // 5m candle appears after first tick; same candle after second tick
        when(candleBuilder.getCompletedCandles(TOKEN, "5m"))
                .thenReturn(List.of(htfCandle));
        when(candleBuilder.getCompletedCandles(TOKEN, "15m")).thenReturn(List.of());

        IndicatorSnapshot current  = buySnapshot();
        IndicatorSnapshot previous = prevForBuyCross();
        when(indicatorEngine.getSnapshot(TOKEN)).thenReturn(current);
        when(indicatorEngine.getPreviousSnapshot(TOKEN)).thenReturn(previous);

        when(signalDetector.evaluate(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        pipeline.processTick(tick(NORMAL_TICK_TS));
        pipeline.processTick(tick(NORMAL_TICK_TS.plusSeconds(60)));

        // onCandleClose called: first1m + htfCandle (once) + second1m = 3 times total
        // htfCandle openTime hasn't changed, so second tick should NOT reprocess it.
        verify(indicatorEngine, times(3)).onCandleClose(anyLong(), any());
    }
}
