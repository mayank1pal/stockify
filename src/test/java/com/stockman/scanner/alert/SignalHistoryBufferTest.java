package com.stockman.scanner.alert;

import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.IndicatorSnapshot;
import com.stockman.scanner.model.SignalEnums.*;
import com.stockman.scanner.model.TradeSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SignalHistoryBufferTest {

    private SignalHistoryBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new SignalHistoryBuffer();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static final IndicatorSnapshot SNAPSHOT = new IndicatorSnapshot(
        9.0, 21.0, 50.0, 200.0,
        55.0,
        0.5, 0.3, 0.2,
        60.0, 55.0,
        2498.0,
        2495.0, true,
        1_000_000.0,
        1.2,
        12.5,
        true
    );

    private TradeSignal makeSignal(String id) {
        return new TradeSignal(
            id, "RELIANCE", 738561L,
            SignalType.BUY, SignalStrength.MODERATE,
            TradingStyle.INTRADAY,
            2500.0, 2480.0, 2530.0,
            List.of("RSI oversold"),
            SNAPSHOT,
            Instant.now()
        );
    }

    private TradeSignal makeSignalAt(String id, Instant timestamp) {
        return new TradeSignal(
            id, "RELIANCE", 738561L,
            SignalType.BUY, SignalStrength.MODERATE,
            TradingStyle.INTRADAY,
            2500.0, 2480.0, 2530.0,
            List.of("RSI oversold"),
            SNAPSHOT,
            timestamp
        );
    }

    // ── addSignal and retrieve ─────────────────────────────────────────────────

    @Test
    void addSignal_retrievable() {
        TradeSignal signal = makeSignal("sig-001");
        buffer.addSignal(signal);

        Optional<TradeSignal> found = buffer.getSignal("sig-001");
        assertThat(found).isPresent();
        assertThat(found.get().signalId()).isEqualTo("sig-001");
    }

    @Test
    void getSignal_unknownId_returnsEmpty() {
        Optional<TradeSignal> found = buffer.getSignal("nonexistent");
        assertThat(found).isEmpty();
    }

    @Test
    void addSignal_duplicateId_latestPreserved() {
        // Deque-based — second add goes to front, first is still in there.
        // getSignal finds first match scanning from front (newest first).
        TradeSignal s1 = makeSignal("dup-id");
        buffer.addSignal(s1);
        buffer.addSignal(s1); // same id added again

        Optional<TradeSignal> found = buffer.getSignal("dup-id");
        assertThat(found).isPresent();
    }

    // ── Bounded eviction at MAX_SIZE ───────────────────────────────────────────

    @Test
    void addSignal_boundedAt500() {
        // Add 501 signals; the oldest (last added in loop = "sig-000") should be evicted.
        // addFirst means the deque grows from the front, pollLast evicts from the tail.
        for (int i = 0; i < 501; i++) {
            buffer.addSignal(makeSignal(String.format("sig-%03d", i)));
        }

        // sig-000 was added first → it's at the tail → should be evicted
        assertThat(buffer.getSignal("sig-000")).isEmpty();
        // sig-500 was added last → it's at the head → should be present
        assertThat(buffer.getSignal("sig-500")).isPresent();
        // sig-001 was the second-oldest — still present since only 1 was evicted
        assertThat(buffer.getSignal("sig-001")).isPresent();
        // sig-002 also still present
        assertThat(buffer.getSignal("sig-002")).isPresent();
    }

    // ── Cursor-based retrieval ─────────────────────────────────────────────────

    @Test
    void getSignalsSince_nullCursor_returnsUpToLimit() {
        for (int i = 0; i < 10; i++) {
            buffer.addSignal(makeSignal("sig-" + i));
        }

        List<TradeSignal> result = buffer.getSignalsSince(null, 5);
        assertThat(result).hasSize(5);
    }

    @Test
    void getSignalsSince_knownCursor_returnsSignalsAfterCursor() {
        // Add signals in order; they're stored newest-first in the deque
        buffer.addSignal(makeSignal("sig-A")); // oldest
        buffer.addSignal(makeSignal("sig-B"));
        buffer.addSignal(makeSignal("sig-C")); // newest (at front)

        // Cursor at sig-B: we want signals that arrived *after* sig-B
        // Deque order: [sig-C, sig-B, sig-A]. "After" sig-B (newer) = [sig-C]
        List<TradeSignal> result = buffer.getSignalsSince("sig-B", 10);
        assertThat(result).extracting(TradeSignal::signalId).containsExactly("sig-C");
    }

    @Test
    void getSignalsSince_cursorAtFront_returnsEmpty() {
        buffer.addSignal(makeSignal("sig-A"));
        buffer.addSignal(makeSignal("sig-B")); // newest

        // Cursor at newest: nothing after it
        List<TradeSignal> result = buffer.getSignalsSince("sig-B", 10);
        assertThat(result).isEmpty();
    }

    @Test
    void getSignalsSince_unknownCursor_returnsUpToLimit() {
        for (int i = 0; i < 5; i++) {
            buffer.addSignal(makeSignal("sig-" + i));
        }

        // Unknown cursor behaves like null → return up to limit from newest
        List<TradeSignal> result = buffer.getSignalsSince("unknown-cursor", 3);
        assertThat(result).hasSize(3);
    }

    @Test
    void getSignalsSince_limitRespected() {
        buffer.addSignal(makeSignal("sig-A")); // oldest
        buffer.addSignal(makeSignal("sig-B"));
        buffer.addSignal(makeSignal("sig-C"));
        buffer.addSignal(makeSignal("sig-D")); // newest

        // After sig-A there are 3 signals (B, C, D), but limit is 2
        List<TradeSignal> result = buffer.getSignalsSince("sig-A", 2);
        assertThat(result).hasSize(2);
    }

    // ── Enrichment co-storage ──────────────────────────────────────────────────

    @Test
    void addEnrichment_retrievable() {
        AiEnrichment enrichment = new AiEnrichment(
            "sig-001", "Strong uptrend", 0.85, new String[]{"technical"}, Instant.now());
        buffer.addEnrichment(enrichment);

        Optional<AiEnrichment> found = buffer.getEnrichment("sig-001");
        assertThat(found).isPresent();
        assertThat(found.get().insight()).isEqualTo("Strong uptrend");
        assertThat(found.get().aiConfidence()).isEqualTo(0.85);
    }

    @Test
    void getEnrichment_unknownId_returnsEmpty() {
        assertThat(buffer.getEnrichment("nonexistent")).isEmpty();
    }

    @Test
    void addEnrichment_overwrite_returnLatest() {
        buffer.addEnrichment(new AiEnrichment("sig-001", "Old insight", 0.5, new String[]{}, Instant.now()));
        buffer.addEnrichment(new AiEnrichment("sig-001", "New insight", 0.9, new String[]{}, Instant.now()));

        Optional<AiEnrichment> found = buffer.getEnrichment("sig-001");
        assertThat(found).isPresent();
        assertThat(found.get().insight()).isEqualTo("New insight");
    }

    // ── clearOlderThan ─────────────────────────────────────────────────────────

    @Test
    void clearOlderThan_removesStaleSignals() {
        Instant cutoff = Instant.now();
        Instant old = cutoff.minusSeconds(3600);
        Instant fresh = cutoff.plusSeconds(60);

        buffer.addSignal(makeSignalAt("old-sig", old));
        buffer.addSignal(makeSignalAt("fresh-sig", fresh));

        buffer.clearOlderThan(cutoff);

        assertThat(buffer.getSignal("old-sig")).isEmpty();
        assertThat(buffer.getSignal("fresh-sig")).isPresent();
    }

    @Test
    void clearOlderThan_removesAssociatedEnrichments() {
        Instant cutoff = Instant.now();
        Instant old = cutoff.minusSeconds(3600);

        buffer.addSignal(makeSignalAt("old-sig", old));
        buffer.addEnrichment(new AiEnrichment("old-sig", "insight", 0.8, new String[]{}, old));

        buffer.clearOlderThan(cutoff);

        assertThat(buffer.getSignal("old-sig")).isEmpty();
        assertThat(buffer.getEnrichment("old-sig")).isEmpty();
    }

    @Test
    void clearOlderThan_emptyBuffer_noop() {
        // Should not throw
        buffer.clearOlderThan(Instant.now());
    }
}
