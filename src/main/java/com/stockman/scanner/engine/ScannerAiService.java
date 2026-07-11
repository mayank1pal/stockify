package com.stockman.scanner.engine;

import com.stockman.config.OpenRouterConfig;
import com.stockman.config.ScannerConfig;
import com.stockman.scanner.event.AiEnrichmentEvent;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.FundamentalData;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.TradeSignal;
import com.stockman.service.OpenRouterModelService;
import com.stockman.service.OpenRouterModelService.AnalysisResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Asynchronously enriches a {@link TradeSignal} with an AI-generated insight.
 *
 * <p>Called after a signal has been emitted by {@link ScannerPipeline}. The enrichment
 * is fire-and-forget: results are broadcast as {@link AiEnrichmentEvent} for downstream
 * consumers (alert channels, WebSocket push, etc.).
 *
 * <p>All calls are gated by {@link AiBudgeter#tryAcquire()} so we respect the configured
 * rate limit. If the budget is exhausted the enrichment is silently skipped.
 */
@Service
@Slf4j
public class ScannerAiService {

    /** Agent key used for scanner enrichment — maps to TECHNICAL model in OpenRouter config. */
    private static final String AGENT_KEY = "technical";

    private final OpenRouterModelService modelService;
    private final OpenRouterConfig openRouterConfig;
    private final AiBudgeter budgeter;
    private final ScannerConfig config;
    private final ApplicationEventPublisher eventPublisher;
    private final Counter aiCalls;
    private final Counter aiTimeouts;

    public ScannerAiService(OpenRouterModelService modelService,
                            OpenRouterConfig openRouterConfig,
                            AiBudgeter budgeter,
                            ScannerConfig config,
                            ApplicationEventPublisher eventPublisher,
                            MeterRegistry meterRegistry) {
        this.modelService = modelService;
        this.openRouterConfig = openRouterConfig;
        this.budgeter = budgeter;
        this.config = config;
        this.eventPublisher = eventPublisher;
        this.aiCalls    = meterRegistry.counter("scanner.ai.calls");
        this.aiTimeouts = meterRegistry.counter("scanner.ai.timeouts");
    }

    /**
     * Enriches the given signal with an AI analysis. Runs on the
     * {@code aiEnrichmentExecutor} thread pool so it never blocks the pipeline.
     *
     * @param signal       the trade signal to enrich
     * @param indicators   the indicator snapshot at signal time
     * @param fundamentals fundamental data for the underlying symbol (may be {@code null})
     */
    @Async("aiEnrichmentExecutor")
    public void enrichSignal(TradeSignal signal,
                             IndicatorSnapshot indicators,
                             FundamentalData fundamentals) {
        if (!config.getAiEnrichment().isEnabled()) {
            log.debug("AI enrichment disabled; skipping signal {}", signal.signalId());
            return;
        }

        if (!budgeter.tryAcquire()) {
            log.debug("AI budget exhausted, skipping enrichment for {}", signal.signalId());
            return;
        }

        try {
            aiCalls.increment();
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(signal, indicators, fundamentals);

            String modelId = openRouterConfig.getModels()
                    .getOrDefault(AGENT_KEY, openRouterConfig.getDefaultModel());

            AnalysisResult result = modelService.analyzeWithModel(modelId, systemPrompt, userPrompt);

            AiEnrichment enrichment = new AiEnrichment(
                    signal.signalId(),
                    result.text(),
                    0.0, // confidence could be parsed from response in future iterations
                    new String[]{AGENT_KEY.toUpperCase()},
                    Instant.now()
            );

            eventPublisher.publishEvent(new AiEnrichmentEvent(this, enrichment));
            log.debug("AI enrichment complete for {}: {} tokens used",
                    signal.signalId(),
                    result.usage() != null
                            ? result.usage().getPromptTokens() + result.usage().getCompletionTokens()
                            : "unknown");

        } catch (Exception e) {
            // Count timeouts separately from other failures
            if (isTimeout(e)) {
                aiTimeouts.increment();
                log.warn("AI enrichment timed out for {}: {}", signal.signalId(), e.getMessage());
            } else {
                log.warn("AI enrichment failed for {}: {}", signal.signalId(), e.getMessage());
            }
        }
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are a concise technical analyst assistant for Indian equity markets.
                Given a scanner-generated trade signal and its indicator context, provide a
                brief (2-4 sentences) analysis that either confirms or questions the signal.
                Focus on: key indicator alignment, potential risks, and confidence level.
                Be direct and actionable. Do not repeat data already provided.
                """.strip();
    }

    private String buildUserPrompt(TradeSignal signal,
                                   IndicatorSnapshot ind,
                                   FundamentalData fund) {
        StringBuilder sb = new StringBuilder();

        // Signal summary
        sb.append("=== SIGNAL ===\n");
        sb.append("Symbol:      ").append(signal.symbol()).append("\n");
        sb.append("Type:        ").append(signal.type()).append("\n");
        sb.append("Strength:    ").append(signal.strength()).append("\n");
        sb.append("Style:       ").append(signal.style()).append("\n");
        sb.append("Entry:       ").append(signal.entryPrice()).append("\n");
        sb.append("Stop Loss:   ").append(signal.stopLoss()).append("\n");
        sb.append("Target:      ").append(signal.target()).append("\n");

        if (signal.triggerReasons() != null && !signal.triggerReasons().isEmpty()) {
            sb.append("Triggers:    ").append(String.join(", ", signal.triggerReasons())).append("\n");
        }

        // Indicator snapshot
        sb.append("\n=== INDICATORS ===\n");
        sb.append("EMA9/21/50:  ").append(fmt(ind.ema9()))
          .append(" / ").append(fmt(ind.ema21()))
          .append(" / ").append(fmt(ind.ema50())).append("\n");
        sb.append("EMA200:      ").append(fmt(ind.ema200())).append("\n");
        sb.append("RSI:         ").append(fmt(ind.rsi())).append("\n");
        sb.append("MACD:        line=").append(fmt(ind.macdLine()))
          .append(" signal=").append(fmt(ind.macdSignal()))
          .append(" hist=").append(fmt(ind.macdHistogram())).append("\n");
        sb.append("Stochastic:  K=").append(fmt(ind.stochasticK()))
          .append(" D=").append(fmt(ind.stochasticD())).append("\n");
        sb.append("VWAP:        ").append(fmt(ind.vwap())).append("\n");
        sb.append("SuperTrend:  ").append(fmt(ind.superTrend()))
          .append(" (").append(ind.superTrendBullish() ? "bullish" : "bearish").append(")\n");
        sb.append("RVOL:        ").append(fmt(ind.rvol())).append("\n");
        sb.append("ATR:         ").append(fmt(ind.atr())).append("\n");

        // Fundamentals (if available)
        if (fund != null && fund.quality() != null) {
            sb.append("\n=== FUNDAMENTALS ===\n");
            if (fund.sector() != null)     sb.append("Sector:      ").append(fund.sector()).append("\n");
            if (fund.pe() != null)         sb.append("P/E:         ").append(fmt(fund.pe())).append("\n");
            if (fund.marketCap() != null)  sb.append("Market Cap:  ").append(fmt(fund.marketCap())).append("\n");
            if (fund.high52w() != null)    sb.append("52w High:    ").append(fmt(fund.high52w())).append("\n");
            if (fund.low52w() != null)     sb.append("52w Low:     ").append(fmt(fund.low52w())).append("\n");
            if (fund.roe() != null)        sb.append("ROE:         ").append(fmt(fund.roe())).append("\n");
        }

        sb.append("\nAnalyse this ").append(signal.type()).append(" signal for ").append(signal.symbol())
          .append(". Is it well-supported by the indicators? Any red flags?");

        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /** Returns {@code true} if the exception looks like a network/read timeout. */
    private static boolean isTimeout(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("read timed");
    }
}
