package com.stockman.scanner.engine;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.event.SignalEvent;
import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.SignalStrength;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.SignalEnums.TradingStyle;
import com.stockman.scanner.model.TickSnapshot;
import com.stockman.scanner.model.TradeSignal;
import com.stockman.scanner.service.InstrumentRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the 7-step per-symbol serialized pipeline on each incoming tick.
 *
 * <p>Called by worker threads — one thread per instrument partition. No internal
 * synchronisation is required provided callers honour the single-writer contract.
 *
 * <p>Pipeline steps:
 * <ol>
 *   <li>Feed tick to {@link CandleBuilder} → collect newly finalized 1m candles.</li>
 *   <li>For each finalized 1m candle check whether a 5m/15m boundary was crossed.</li>
 *   <li>For every (timeframe, candle) pair: {@link IndicatorEngine#onCandleClose}.</li>
 *   <li>For every timeframe: {@link SignalDetector#evaluate}.</li>
 *   <li>Silence-period gate: suppress signals before 09:16:30 IST.</li>
 *   <li>Warm-up gate: suppress signals if within {@code warmupMinutes} of market open.</li>
 *   <li>Cooldown check + record + publish {@link SignalEvent}.</li>
 * </ol>
 */
@Slf4j
@Service
public class ScannerPipeline {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Separate IndicatorEngines per timeframe so indicators remain timeframe-coherent.
    // The injected indicatorEngine is used for 1m; HTF engines are created on demand.
    // Key: "token:timeframe" → last processed candle openTime (to avoid double-processing).
    private final Map<String, Instant> lastProcessedHtf = new HashMap<>();

    private final CandleBuilder candleBuilder;
    private final IndicatorEngine indicatorEngine;
    private final SignalDetector signalDetector;
    private final SignalCooldownManager cooldownManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ScannerConfig config;
    private final InstrumentRegistry instrumentRegistry;
    private final MeterRegistry meterRegistry;
    // Metrics counter is looked up per-tag at publish time; no pre-created Counter field.

    public ScannerPipeline(CandleBuilder candleBuilder,
                           IndicatorEngine indicatorEngine,
                           SignalDetector signalDetector,
                           SignalCooldownManager cooldownManager,
                           ApplicationEventPublisher eventPublisher,
                           ScannerConfig config,
                           InstrumentRegistry instrumentRegistry,
                           MeterRegistry meterRegistry) {
        this.candleBuilder = candleBuilder;
        this.indicatorEngine = indicatorEngine;
        this.signalDetector = signalDetector;
        this.cooldownManager = cooldownManager;
        this.eventPublisher = eventPublisher;
        this.config = config;
        this.instrumentRegistry = instrumentRegistry;
        this.meterRegistry = meterRegistry;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Main entry point — called by worker threads once per tick.
     *
     * @param tick the incoming market tick
     */
    public void processTick(TickSnapshot tick) {
        long token = tick.instrumentToken();

        // Step 1: resolve symbol
        String symbol = instrumentRegistry.getSymbol(token);
        if (symbol == null) {
            log.debug("No symbol registered for token {}; skipping tick", token);
            return;
        }

        // Step 2: feed tick to CandleBuilder — returns newly finalized 1m candles
        List<Candle> finalized1m = candleBuilder.onTick(tick);

        if (finalized1m.isEmpty()) {
            return; // Still accumulating the current minute — nothing to evaluate
        }

        // Steps 3–7 for each finalized 1m candle
        for (Candle oneMinCandle : finalized1m) {
            processCandle(token, symbol, oneMinCandle, "1m", tick.exchangeTimestamp());
        }

        // Check higher-timeframe completions after 1m candles have been stored.
        // CandleBuilder aggregates 5m/15m internally; we query the completed deques.
        checkHigherTimeframe(token, symbol, "5m", tick.exchangeTimestamp());
        checkHigherTimeframe(token, symbol, "15m", tick.exchangeTimestamp());
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Look at the most-recently completed candle for the given higher timeframe.
     * If we haven't processed it yet (tracked via {@link #lastProcessedHtf}),
     * run indicators and signal detection on it.
     */
    private void checkHigherTimeframe(long token, String symbol,
                                       String timeframe, Instant tickTs) {
        List<Candle> window = candleBuilder.getCompletedCandles(token, timeframe);
        if (window.isEmpty()) return;

        Candle latest = window.get(window.size() - 1);
        String key = token + ":" + timeframe;

        Instant lastProcessed = lastProcessedHtf.get(key);
        if (lastProcessed != null && !latest.openTime().isAfter(lastProcessed)) {
            return; // Already processed this HTF candle
        }

        lastProcessedHtf.put(key, latest.openTime());
        processCandle(token, symbol, latest, timeframe, tickTs);
    }

    /**
     * Run the indicator + signal pipeline for a single (candle, timeframe) pair.
     */
    private void processCandle(long token, String symbol, Candle candle,
                                String timeframe, Instant tickTs) {

        // Step 3: update indicators
        indicatorEngine.onCandleClose(token, candle);

        // Step 4: read snapshots
        IndicatorSnapshot current = indicatorEngine.getSnapshot(token);
        IndicatorSnapshot previous = indicatorEngine.getPreviousSnapshot(token);

        if (current == null || previous == null) {
            return; // Not enough candles yet for crossover detection
        }

        // Step 5: check indicator warm-up
        if (!current.warmedUp()) {
            log.debug("Indicators not warmed up for {} [{}]; skipping signal", symbol, timeframe);
            return;
        }

        // Step 6a: silence period — suppress signals before 09:16:30 IST
        if (isInSilencePeriod(tickTs)) {
            log.debug("Silence period active for {} [{}]; suppressing signal", symbol, timeframe);
            return;
        }

        // Step 6b: warm-up suppression — skip if within warmupMinutes of market open
        if (isInWarmupPeriod(tickTs)) {
            log.debug("Warm-up period active for {} [{}]; suppressing signal", symbol, timeframe);
            return;
        }

        // Step 7: run signal detector
        Optional<TradeSignal> signalOpt = signalDetector.evaluate(
                token, symbol, candle, timeframe, current, previous, null);

        signalOpt.ifPresent(this::maybePublish);
    }

    /**
     * Check cooldown, record, and publish the signal event if permitted.
     */
    private void maybePublish(TradeSignal signal) {
        String symbol      = signal.symbol();
        TradingStyle style = signal.style();
        String timeframe   = resolveTimeframe(style);
        SignalType type    = signal.type();
        SignalStrength strength = signal.strength();

        if (!cooldownManager.canEmitSignal(symbol, style, timeframe, type, strength)) {
            log.debug("Cooldown blocked signal for {} [{}] {}", symbol, timeframe, type);
            return;
        }

        cooldownManager.recordSignal(symbol, style, timeframe, type, strength);

        boolean aiEnabled = config.getAiEnrichment().isEnabled();
        SignalEvent event = new SignalEvent(this, signal, aiEnabled);
        eventPublisher.publishEvent(event);

        // Increment counter tagged by style and strength
        meterRegistry.counter("scanner.signals.generated",
                "style", style.name().toLowerCase(),
                "strength", strength.name().toLowerCase()).increment();

        log.info("Published SignalEvent: {} {} {} aiPending={}", type, symbol, timeframe, aiEnabled);
    }

    // ── Time gates ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the tick timestamp is before the configured silence-period end.
     * Default: 09:16:30 IST.
     */
    boolean isInSilencePeriod(Instant ts) {
        LocalTime tickTime   = ts.atZone(IST).toLocalTime();
        LocalTime silenceEnd = LocalTime.parse(config.getSilencePeriodEnd(), TIME_FMT);
        return tickTime.isBefore(silenceEnd);
    }

    /**
     * Returns {@code true} if the tick timestamp is within {@code warmupMinutes} of market
     * open (09:15 IST) but after the silence period, i.e. [09:16:30, 09:15+warmupMinutes).
     */
    boolean isInWarmupPeriod(Instant ts) {
        LocalTime tickTime   = ts.atZone(IST).toLocalTime();
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime warmupEnd  = marketOpen.plusMinutes(config.getWarmupMinutes());
        return !tickTime.isBefore(marketOpen) && tickTime.isBefore(warmupEnd);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    /** Map TradingStyle back to timeframe string for cooldown key construction. */
    private static String resolveTimeframe(TradingStyle style) {
        return switch (style) {
            case SCALP    -> "1m";
            case INTRADAY -> "5m";
            case SWING    -> "15m";
        };
    }
}
