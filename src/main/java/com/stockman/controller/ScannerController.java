package com.stockman.controller;

import com.stockman.scanner.alert.SignalHistoryBuffer;
import com.stockman.scanner.engine.IndicatorEngine;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.TradeSignal;
import com.stockman.scanner.service.AuthStateManager;
import com.stockman.scanner.service.InstrumentRegistry;
import com.stockman.scanner.service.TickerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/scanner")
@RequiredArgsConstructor
@Slf4j
public class ScannerController {

    private final SignalHistoryBuffer signalHistory;
    private final IndicatorEngine indicatorEngine;
    private final InstrumentRegistry instrumentRegistry;
    private final TickerService tickerService;
    private final AuthStateManager authStateManager;
    private final MeterRegistry meterRegistry;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected", authStateManager.isActive());
        status.put("authState", authStateManager.getState().name());
        status.put("instrumentCount", instrumentRegistry.size());

        // Count signals generated today
        Instant startOfDay = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
        List<TradeSignal> allSignals = signalHistory.getSignalsSince(null, 500);
        long signalsToday = allSignals.stream()
                .filter(s -> s.generatedAt().isAfter(startOfDay))
                .count();
        status.put("signalsToday", signalsToday);

        // Last tick time from most recent signal as a proxy (null if no signals)
        Optional<TradeSignal> latest = allSignals.stream().findFirst();
        status.put("lastTickTime", latest.map(s -> s.generatedAt().toString()).orElse(null));

        // Key metrics from MeterRegistry
        status.put("metrics", collectKeyMetrics());

        log.debug("Scanner status requested: authState={} instruments={}",
                authStateManager.getState(), instrumentRegistry.size());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/signals")
    public ResponseEntity<List<Map<String, Object>>> getSignals(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {

        int clampedLimit = Math.min(Math.max(limit, 1), 200);
        List<TradeSignal> signals = signalHistory.getSignalsSince(cursor, clampedLimit);

        List<Map<String, Object>> result = new ArrayList<>(signals.size());
        for (TradeSignal signal : signals) {
            Map<String, Object> entry = signalToMap(signal);
            signalHistory.getEnrichment(signal.signalId()).ifPresent(e ->
                    entry.put("enrichment", enrichmentToMap(e)));
            result.add(entry);
        }

        log.debug("GET /signals cursor={} limit={} returned={}", cursor, clampedLimit, result.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/signals/{signalId}")
    public ResponseEntity<Map<String, Object>> getSignal(@PathVariable String signalId) {
        Optional<TradeSignal> signalOpt = signalHistory.getSignal(signalId);
        if (signalOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> entry = signalToMap(signalOpt.get());
        signalHistory.getEnrichment(signalId).ifPresent(e ->
                entry.put("enrichment", enrichmentToMap(e)));

        return ResponseEntity.ok(entry);
    }

    @GetMapping("/indicators/{symbol}")
    public ResponseEntity<?> getIndicators(@PathVariable String symbol) {
        Long token = instrumentRegistry.getToken(symbol);
        if (token == null) {
            return ResponseEntity.notFound().build();
        }
        IndicatorSnapshot snap = indicatorEngine.getSnapshot(token);
        if (snap == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snap);
    }

    // ── Metrics helpers ────────────────────────────────────────────────────────

    private Map<String, Object> collectKeyMetrics() {
        Map<String, Object> m = new HashMap<>();
        m.put("ticksReceived",  getCounterValue("scanner.ticks.received"));
        m.put("ticksDropped",   getCounterValue("scanner.ticks.dropped"));
        m.put("ticksStale",     getCounterValue("scanner.ticks.stale"));
        m.put("queueSize",      getGaugeValue("scanner.queue.size"));
        m.put("signalsGenerated", getCounterValue("scanner.signals.generated"));
        m.put("alertsSent",     getCounterValue("scanner.alerts.sent"));
        m.put("alertsFailed",   getCounterValue("scanner.alerts.failed"));
        m.put("alertsDropped",  getCounterValue("scanner.alerts.dropped"));
        m.put("aiCalls",        getCounterValue("scanner.ai.calls"));
        m.put("aiTimeouts",     getCounterValue("scanner.ai.timeouts"));
        return m;
    }

    /** Sums all counters with the given name across all tag combinations. */
    private double getCounterValue(String name) {
        return Search.in(meterRegistry).name(name).counters()
                .stream().mapToDouble(Counter::count).sum();
    }

    private double getGaugeValue(String name) {
        io.micrometer.core.instrument.Gauge gauge =
                meterRegistry.find(name).gauge();
        return gauge == null ? 0.0 : gauge.value();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> signalToMap(TradeSignal signal) {
        Map<String, Object> map = new HashMap<>();
        map.put("signalId", signal.signalId());
        map.put("symbol", signal.symbol());
        map.put("instrumentToken", signal.instrumentToken());
        map.put("type", signal.type().name());
        map.put("strength", signal.strength().name());
        map.put("style", signal.style().name());
        map.put("entryPrice", signal.entryPrice());
        map.put("stopLoss", signal.stopLoss());
        map.put("target", signal.target());
        map.put("triggerReasons", signal.triggerReasons());
        map.put("generatedAt", signal.generatedAt().toString());
        return map;
    }

    private Map<String, Object> enrichmentToMap(AiEnrichment enrichment) {
        Map<String, Object> map = new HashMap<>();
        map.put("signalId", enrichment.signalId());
        map.put("insight", enrichment.insight());
        map.put("aiConfidence", enrichment.aiConfidence());
        map.put("agentsUsed", enrichment.agentsUsed());
        map.put("completedAt", enrichment.completedAt().toString());
        return map;
    }
}
