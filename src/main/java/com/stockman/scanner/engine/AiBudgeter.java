package com.stockman.scanner.engine;

import com.google.common.util.concurrent.RateLimiter;
import com.stockman.config.ScannerConfig;
import com.stockman.scanner.model.SignalEnums.SignalStrength;
import com.stockman.scanner.model.SignalEnums.TradingStyle;
import com.stockman.scanner.model.TradeSignal;
import org.springframework.stereotype.Component;

/**
 * Manages the AI enrichment budget: rate-limits outbound LLM calls and
 * assigns numeric priorities to signals so high-value signals are enriched
 * first when the budget is constrained.
 *
 * <p>Rate limit is taken from {@code scanner.ai-enrichment.max-calls-per-minute}.
 *
 * <p>Priority scoring:
 * <ul>
 *   <li>STRONG strength  → +100</li>
 *   <li>MODERATE strength → +50</li>
 *   <li>SWING style       → +30</li>
 *   <li>INTRADAY style    → +20</li>
 * </ul>
 */
@Component
public class AiBudgeter {

    private final RateLimiter rateLimiter;

    public AiBudgeter(ScannerConfig config) {
        int maxPerMinute = config.getAiEnrichment().getMaxCallsPerMinute();
        double permitsPerSecond = maxPerMinute / 60.0;
        this.rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    /**
     * Non-blocking acquire. Returns {@code true} if the AI call should proceed,
     * {@code false} if the rate limit is currently exhausted.
     */
    public boolean tryAcquire() {
        return rateLimiter.tryAcquire();
    }

    /**
     * Returns a numeric priority for the given signal.
     * Higher values mean the signal is more important to enrich.
     *
     * <p>Callers can use this to build a priority queue when multiple signals
     * are competing for the limited AI budget.
     *
     * @param signal the trade signal to prioritise
     * @return non-negative integer priority (higher = more important)
     */
    public int getPriority(TradeSignal signal) {
        int priority = 0;

        SignalStrength strength = signal.strength();
        if (strength == SignalStrength.STRONG) {
            priority += 100;
        } else if (strength == SignalStrength.MODERATE) {
            priority += 50;
        }
        // WEAK adds 0

        TradingStyle style = signal.style();
        if (style == TradingStyle.SWING) {
            priority += 30;
        } else if (style == TradingStyle.INTRADAY) {
            priority += 20;
        }
        // SCALP adds 0

        return priority;
    }
}
