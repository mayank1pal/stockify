package com.stockman.scanner.alert;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.DeliveryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-signalId × channel delivery state machine.
 * <p>
 * Key format: {@code "<signalId>:<channel>"} — e.g.
 * {@code "RELIANCE:INTRADAY:5m:1234567890:BUY:telegram"}
 * <p>
 * Valid transitions: NEW → SENT (with messageRef) → ENRICHED / FAILED / STALE.
 * All state changes are CAS-based to prevent duplicate delivery races.
 */
@Slf4j
@Component
public class AlertDeliveryState {

    /** Key: signalId + ":" + channel */
    private final ConcurrentHashMap<String, AtomicReference<DeliveryEntry>> states =
        new ConcurrentHashMap<>();

    /** Short-lived buffer for enrichments that arrive before the message is sent (20s TTL). */
    private final Cache<String, AiEnrichment> pendingEnrichments;

    public AlertDeliveryState() {
        this.pendingEnrichments = Caffeine.newBuilder()
            .expireAfterWrite(20, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();
    }

    // ── State access ───────────────────────────────────────────────────────────

    /**
     * Returns the existing entry for the given (signalId, channel) pair, or creates a
     * NEW entry if none exists yet. Idempotent.
     */
    public DeliveryEntry getOrCreate(String signalId, String channel) {
        String key = stateKey(signalId, channel);
        AtomicReference<DeliveryEntry> ref = states.computeIfAbsent(
            key, k -> new AtomicReference<>(new DeliveryEntry(DeliveryStatus.NEW, null, Instant.now()))
        );
        return ref.get();
    }

    // ── Transitions ────────────────────────────────────────────────────────────

    /**
     * CAS transition: NEW → SENT.
     *
     * @param messageRef platform message ID (e.g. Telegram message_id, Discord message snowflake)
     * @return {@code true} if the transition succeeded; {@code false} if the entry was not in NEW
     *         state or does not exist.
     */
    public boolean transitionToSent(String signalId, String channel, String messageRef) {
        String key = stateKey(signalId, channel);
        AtomicReference<DeliveryEntry> ref = states.get(key);
        if (ref == null) {
            log.debug("transitionToSent: no entry found for key={}", key);
            return false;
        }

        DeliveryEntry current = ref.get();
        if (current.status() != DeliveryStatus.NEW) {
            log.debug("transitionToSent: expected NEW but found {} for key={}", current.status(), key);
            return false;
        }

        DeliveryEntry next = new DeliveryEntry(DeliveryStatus.SENT, messageRef, Instant.now());
        boolean success = ref.compareAndSet(current, next);
        if (success) {
            log.debug("transitionToSent: key={} messageRef={}", key, messageRef);
        }
        return success;
    }

    /**
     * CAS transition: SENT → ENRICHED.
     *
     * @return {@code true} if the transition succeeded; {@code false} if the entry was not in SENT
     *         state or does not exist.
     */
    public boolean transitionToEnriched(String signalId, String channel) {
        String key = stateKey(signalId, channel);
        AtomicReference<DeliveryEntry> ref = states.get(key);
        if (ref == null) {
            return false;
        }

        DeliveryEntry current = ref.get();
        if (current.status() != DeliveryStatus.SENT) {
            log.debug("transitionToEnriched: expected SENT but found {} for key={}", current.status(), key);
            return false;
        }

        DeliveryEntry next = new DeliveryEntry(DeliveryStatus.ENRICHED, current.messageRef(), Instant.now());
        boolean success = ref.compareAndSet(current, next);
        if (success) {
            log.debug("transitionToEnriched: key={}", key);
        }
        return success;
    }

    // ── Pending enrichment buffer ──────────────────────────────────────────────

    /**
     * Buffers an AI enrichment that arrived before its associated message was sent.
     * Expires after 20 s (Caffeine TTL).
     */
    public void bufferEnrichment(String signalId, AiEnrichment enrichment) {
        pendingEnrichments.put(signalId, enrichment);
        log.debug("bufferEnrichment: signalId={}", signalId);
    }

    /**
     * Returns a buffered enrichment, or {@code null} if none exists or it has expired.
     */
    public AiEnrichment getPendingEnrichment(String signalId) {
        return pendingEnrichments.getIfPresent(signalId);
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private static String stateKey(String signalId, String channel) {
        return signalId + ":" + channel;
    }

    // ── Value type ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a single delivery attempt's current state.
     *
     * @param status     current delivery status in the state machine
     * @param messageRef platform-specific message identifier (non-null after SENT transition)
     * @param timestamp  when this entry was last updated
     */
    public record DeliveryEntry(DeliveryStatus status, String messageRef, Instant timestamp) {}
}
