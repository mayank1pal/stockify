package com.stockman.scanner.alert;

import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDeliveryStateTest {

    private AlertDeliveryState deliveryState;

    @BeforeEach
    void setUp() {
        deliveryState = new AlertDeliveryState();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static final String SIGNAL_ID = "RELIANCE:INTRADAY:5m:1234567890:BUY";
    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CHANNEL_DISCORD = "discord";

    private AiEnrichment makeEnrichment(String signalId) {
        return new AiEnrichment(signalId, "Strong uptrend", 0.87, new String[]{"technical"}, Instant.now());
    }

    // ── getOrCreate ────────────────────────────────────────────────────────────

    @Test
    void getOrCreate_newEntry_statusIsNew() {
        AlertDeliveryState.DeliveryEntry entry = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);

        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo(DeliveryStatus.NEW);
        assertThat(entry.messageRef()).isNull();
        assertThat(entry.timestamp()).isNotNull();
    }

    @Test
    void getOrCreate_idempotent_returnsSameStatus() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        AlertDeliveryState.DeliveryEntry second = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);

        assertThat(second.status()).isEqualTo(DeliveryStatus.NEW);
    }

    @Test
    void getOrCreate_differentChannels_independentEntries() {
        AlertDeliveryState.DeliveryEntry telegram = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        AlertDeliveryState.DeliveryEntry discord = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_DISCORD);

        // Both start as NEW but are independent entries
        assertThat(telegram.status()).isEqualTo(DeliveryStatus.NEW);
        assertThat(discord.status()).isEqualTo(DeliveryStatus.NEW);

        // Transitioning one does not affect the other
        deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref-123");
        AlertDeliveryState.DeliveryEntry updatedDiscord = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_DISCORD);
        assertThat(updatedDiscord.status()).isEqualTo(DeliveryStatus.NEW);
    }

    // ── transitionToSent ───────────────────────────────────────────────────────

    @Test
    void transitionToSent_fromNew_succeeds() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);

        boolean result = deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref-001");

        assertThat(result).isTrue();
        AlertDeliveryState.DeliveryEntry entry = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        assertThat(entry.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(entry.messageRef()).isEqualTo("msg-ref-001");
    }

    @Test
    void transitionToSent_fromSent_returnsFalse() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref-001");

        // Second transition should fail (already SENT)
        boolean result = deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref-002");

        assertThat(result).isFalse();
        // Original messageRef preserved
        AlertDeliveryState.DeliveryEntry entry = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        assertThat(entry.messageRef()).isEqualTo("msg-ref-001");
    }

    @Test
    void transitionToSent_nonExistentEntry_returnsFalse() {
        boolean result = deliveryState.transitionToSent("unknown-signal", CHANNEL_TELEGRAM, "msg-ref");

        assertThat(result).isFalse();
    }

    // ── transitionToEnriched ───────────────────────────────────────────────────

    @Test
    void transitionToEnriched_fromSent_succeeds() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref-001");

        boolean result = deliveryState.transitionToEnriched(SIGNAL_ID, CHANNEL_TELEGRAM);

        assertThat(result).isTrue();
        AlertDeliveryState.DeliveryEntry entry = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        assertThat(entry.status()).isEqualTo(DeliveryStatus.ENRICHED);
    }

    @Test
    void transitionToEnriched_fromNew_returnsFalse() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);

        boolean result = deliveryState.transitionToEnriched(SIGNAL_ID, CHANNEL_TELEGRAM);

        assertThat(result).isFalse();
        AlertDeliveryState.DeliveryEntry entry = deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        assertThat(entry.status()).isEqualTo(DeliveryStatus.NEW);
    }

    @Test
    void transitionToEnriched_alreadyEnriched_returnsFalse() {
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "msg-ref");
        deliveryState.transitionToEnriched(SIGNAL_ID, CHANNEL_TELEGRAM);

        boolean result = deliveryState.transitionToEnriched(SIGNAL_ID, CHANNEL_TELEGRAM);

        assertThat(result).isFalse();
    }

    @Test
    void fullCasChain_newToSentToEnriched() {
        // Simulate the full happy path
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        assertThat(deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM).status())
            .isEqualTo(DeliveryStatus.NEW);

        assertThat(deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "tg-msg-42")).isTrue();
        assertThat(deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM).status())
            .isEqualTo(DeliveryStatus.SENT);

        assertThat(deliveryState.transitionToEnriched(SIGNAL_ID, CHANNEL_TELEGRAM)).isTrue();
        assertThat(deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM).status())
            .isEqualTo(DeliveryStatus.ENRICHED);
    }

    // ── Pending enrichment buffering ───────────────────────────────────────────

    @Test
    void bufferEnrichment_beforeSent_retrievable() {
        AiEnrichment enrichment = makeEnrichment(SIGNAL_ID);
        deliveryState.bufferEnrichment(SIGNAL_ID, enrichment);

        AiEnrichment retrieved = deliveryState.getPendingEnrichment(SIGNAL_ID);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.signalId()).isEqualTo(SIGNAL_ID);
        assertThat(retrieved.insight()).isEqualTo("Strong uptrend");
    }

    @Test
    void getPendingEnrichment_noneBuffered_returnsNull() {
        AiEnrichment result = deliveryState.getPendingEnrichment("no-enrichment-signal");
        assertThat(result).isNull();
    }

    @Test
    void bufferEnrichment_overwrite_returnsLatest() {
        AiEnrichment first = new AiEnrichment(SIGNAL_ID, "First insight", 0.5, new String[]{}, Instant.now());
        AiEnrichment second = new AiEnrichment(SIGNAL_ID, "Second insight", 0.9, new String[]{}, Instant.now());

        deliveryState.bufferEnrichment(SIGNAL_ID, first);
        deliveryState.bufferEnrichment(SIGNAL_ID, second);

        AiEnrichment retrieved = deliveryState.getPendingEnrichment(SIGNAL_ID);
        assertThat(retrieved.insight()).isEqualTo("Second insight");
    }

    @Test
    void pendingEnrichment_retrievableAfterSentTransition() {
        // Buffer enrichment arrives before the message is sent
        AiEnrichment enrichment = makeEnrichment(SIGNAL_ID);
        deliveryState.bufferEnrichment(SIGNAL_ID, enrichment);

        // Message gets sent later
        deliveryState.getOrCreate(SIGNAL_ID, CHANNEL_TELEGRAM);
        deliveryState.transitionToSent(SIGNAL_ID, CHANNEL_TELEGRAM, "tg-msg-99");

        // Pending enrichment should still be retrievable to apply to the sent message
        AiEnrichment pending = deliveryState.getPendingEnrichment(SIGNAL_ID);
        assertThat(pending).isNotNull();
        assertThat(pending.signalId()).isEqualTo(SIGNAL_ID);
    }

    // ── Key isolation ──────────────────────────────────────────────────────────

    @Test
    void differentSignals_differentChannels_fullyIsolated() {
        String signalA = "RELIANCE:INTRADAY:5m:111:BUY";
        String signalB = "HDFC:SWING:15m:222:SELL";

        deliveryState.getOrCreate(signalA, CHANNEL_TELEGRAM);
        deliveryState.getOrCreate(signalB, CHANNEL_DISCORD);

        deliveryState.transitionToSent(signalA, CHANNEL_TELEGRAM, "ref-A");

        // signalB / discord entry is unaffected
        AlertDeliveryState.DeliveryEntry entryB = deliveryState.getOrCreate(signalB, CHANNEL_DISCORD);
        assertThat(entryB.status()).isEqualTo(DeliveryStatus.NEW);
    }
}
