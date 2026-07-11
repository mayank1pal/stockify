package com.stockman.scanner.alert;

import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bounded, thread-safe ring buffer of the last {@value #MAX_SIZE} {@link TradeSignal}s.
 * <p>
 * Signals are stored newest-first (LIFO front-insertion). AI enrichments are stored in a
 * companion map keyed by {@code signalId}.
 * <p>
 * Used by the WebSocket reconnect catch-up path: a client reconnecting after a brief
 * disconnect supplies its last known {@code signalId} as a cursor and receives all signals
 * that arrived since then, up to {@code limit}.
 */
@Slf4j
@Component
public class SignalHistoryBuffer {

    static final int MAX_SIZE = 500;

    /** Ordered newest-first. ConcurrentLinkedDeque provides wait-free reads. */
    private final ConcurrentLinkedDeque<TradeSignal> signals = new ConcurrentLinkedDeque<>();

    /** signalId → enrichment */
    private final ConcurrentHashMap<String, AiEnrichment> enrichments = new ConcurrentHashMap<>();

    // ── Mutations ──────────────────────────────────────────────────────────────

    /**
     * Prepends {@code signal} to the front of the buffer (newest first) and evicts the
     * oldest entry when the buffer exceeds {@value #MAX_SIZE}.
     */
    public void addSignal(TradeSignal signal) {
        signals.addFirst(signal);
        // Trim tail until within MAX_SIZE. In a concurrent setting multiple threads may
        // attempt this simultaneously; the extra polls are harmless.
        while (signals.size() > MAX_SIZE) {
            signals.pollLast();
        }
        log.debug("addSignal: signalId={} bufferSize={}", signal.signalId(), signals.size());
    }

    /**
     * Stores or replaces the enrichment for the given signal. Overwrites silently if a
     * previous enrichment exists.
     */
    public void addEnrichment(AiEnrichment enrichment) {
        enrichments.put(enrichment.signalId(), enrichment);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    /**
     * Cursor-based fetch: returns signals that are *newer* than {@code cursorSignalId}, up
     * to {@code limit} entries, in newest-first order.
     * <p>
     * If {@code cursorSignalId} is {@code null} or not found in the buffer the method
     * returns the most recent {@code limit} signals (standard "first page" behaviour).
     *
     * @param cursorSignalId the signalId of the last signal the client already has, or
     *                       {@code null} for the initial load
     * @param limit          maximum number of signals to return (inclusive)
     * @return unmodifiable list of signals, newest first
     */
    public List<TradeSignal> getSignalsSince(String cursorSignalId, int limit) {
        if (cursorSignalId == null) {
            return headSlice(limit);
        }

        // Scan from newest to find the cursor position.
        // Collect signals that appear *before* the cursor in the deque (i.e. newer).
        List<TradeSignal> result = new ArrayList<>();
        for (TradeSignal signal : signals) {
            if (signal.signalId().equals(cursorSignalId)) {
                // Found the cursor; everything already collected is newer.
                return Collections.unmodifiableList(
                    result.size() <= limit ? result : result.subList(0, limit));
            }
            if (result.size() < limit) {
                result.add(signal);
            }
        }

        // Cursor not found → fall back to first-page behaviour
        return headSlice(limit);
    }

    /**
     * Looks up a single signal by its {@code signalId}.
     *
     * @return the matching signal, or empty if not in the buffer
     */
    public Optional<TradeSignal> getSignal(String signalId) {
        for (TradeSignal signal : signals) {
            if (signal.signalId().equals(signalId)) {
                return Optional.of(signal);
            }
        }
        return Optional.empty();
    }

    /**
     * Looks up the AI enrichment for the given {@code signalId}.
     *
     * @return the enrichment, or empty if not yet stored
     */
    public Optional<AiEnrichment> getEnrichment(String signalId) {
        return Optional.ofNullable(enrichments.get(signalId));
    }

    // ── Maintenance ────────────────────────────────────────────────────────────

    /**
     * Removes all signals whose {@code generatedAt} timestamp is strictly before
     * {@code cutoff}, and also removes their associated enrichments.
     * <p>
     * Intended for day-end cleanup to reclaim memory.
     */
    public void clearOlderThan(Instant cutoff) {
        Iterator<TradeSignal> it = signals.iterator();
        while (it.hasNext()) {
            TradeSignal signal = it.next();
            if (signal.generatedAt().isBefore(cutoff)) {
                it.remove();
                enrichments.remove(signal.signalId());
            }
        }
        log.debug("clearOlderThan: cutoff={} remainingSignals={}", cutoff, signals.size());
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /** Returns up to {@code limit} signals from the head (newest first). */
    private List<TradeSignal> headSlice(int limit) {
        List<TradeSignal> result = new ArrayList<>(Math.min(limit, signals.size()));
        for (TradeSignal signal : signals) {
            if (result.size() >= limit) break;
            result.add(signal);
        }
        return Collections.unmodifiableList(result);
    }
}
