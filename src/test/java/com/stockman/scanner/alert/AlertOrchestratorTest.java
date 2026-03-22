package com.stockman.scanner.alert;

import com.stockman.config.AlertConfig;
import com.stockman.scanner.event.AiEnrichmentEvent;
import com.stockman.scanner.event.SignalEvent;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.DeliveryStatus;
import com.stockman.scanner.model.SignalEnums.SignalStrength;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.SignalEnums.TradingStyle;
import com.stockman.scanner.model.TradeSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertOrchestratorTest {

    @Mock
    private TelegramAlertChannel telegramChannel;

    @Mock
    private DiscordAlertChannel discordChannel;

    @Mock
    private WebSocketAlertChannel webSocketChannel;

    // Real implementations — these are lightweight in-memory structures.
    private AlertDeliveryState deliveryState;
    private SignalHistoryBuffer historyBuffer;
    private AlertConfig alertConfig;

    private AlertOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        deliveryState = new AlertDeliveryState();
        historyBuffer = new SignalHistoryBuffer();

        alertConfig = new AlertConfig();
        AlertConfig.DiscordConfig discordConfig = new AlertConfig.DiscordConfig();
        discordConfig.setChannelIds(Map.of(
                "scalp", "ch-scalp-001",
                "intraday", "ch-intraday-001",
                "swing", "ch-swing-001"
        ));
        alertConfig.setDiscord(discordConfig);

        // Both optional channels wired — individual tests can omit them by creating a separate orchestrator.
        orchestrator = new AlertOrchestrator(
                telegramChannel,
                discordChannel,
                webSocketChannel,
                deliveryState,
                historyBuffer,
                alertConfig,
                new SimpleMeterRegistry()
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private TradeSignal freshSignal(TradingStyle style) {
        return new TradeSignal(
                "RELIANCE:" + style + ":5m:1234:BUY",
                "RELIANCE",
                12345L,
                SignalType.BUY,
                SignalStrength.STRONG,
                style,
                2500.0, 2480.0, 2550.0,
                List.of("RSI oversold", "MACD cross"),
                null,
                Instant.now()
        );
    }

    private TradeSignal staleSignal(TradingStyle style, long ageMs) {
        return new TradeSignal(
                "RELIANCE:" + style + ":5m:9999:BUY",
                "RELIANCE",
                12345L,
                SignalType.BUY,
                SignalStrength.MODERATE,
                style,
                2500.0, 2480.0, 2550.0,
                List.of(),
                null,
                Instant.now().minusMillis(ageMs)
        );
    }

    private AiEnrichment enrichmentFor(String signalId) {
        return new AiEnrichment(signalId, "Strong uptrend expected", 0.82,
                new String[]{"technical", "fundamental"}, Instant.now());
    }

    private SignalEvent signalEvent(TradeSignal signal) {
        return new SignalEvent(this, signal, true);
    }

    private AiEnrichmentEvent enrichmentEvent(AiEnrichment enrichment) {
        return new AiEnrichmentEvent(this, enrichment);
    }

    // ── isStale ────────────────────────────────────────────────────────────────

    @Test
    void isStale_freshScalpSignal_returnsFalse() {
        TradeSignal signal = freshSignal(TradingStyle.SCALP);
        assertThat(orchestrator.isStale(signal)).isFalse();
    }

    @Test
    void isStale_staleScalpSignal_returnsTrue() {
        TradeSignal signal = staleSignal(TradingStyle.SCALP, 31_000);
        assertThat(orchestrator.isStale(signal)).isTrue();
    }

    @Test
    void isStale_staleIntradaySignal_returnsTrue() {
        TradeSignal signal = staleSignal(TradingStyle.INTRADAY, 61_000);
        assertThat(orchestrator.isStale(signal)).isTrue();
    }

    @Test
    void isStale_freshIntradaySignal_returnsFalse() {
        TradeSignal signal = staleSignal(TradingStyle.INTRADAY, 30_000); // 30s < 60s threshold
        assertThat(orchestrator.isStale(signal)).isFalse();
    }

    @Test
    void isStale_staleSwingSignal_returnsTrue() {
        TradeSignal signal = staleSignal(TradingStyle.SWING, 301_000);
        assertThat(orchestrator.isStale(signal)).isTrue();
    }

    // ── onSignal: fresh signal dispatching ─────────────────────────────────────

    @Test
    void onSignal_freshSignal_webSocketAlwaysReceivesSignal() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-1");
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-1");

        orchestrator.onSignal(signalEvent(signal));

        verify(webSocketChannel).sendSignal(eq(signal), anyBoolean());
    }

    @Test
    void onSignal_freshSignal_addedToHistoryBuffer() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-2");
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-2");

        orchestrator.onSignal(signalEvent(signal));

        assertThat(historyBuffer.getSignal(signal.signalId())).isPresent();
    }

    @Test
    void onSignal_freshSignal_telegramAndDiscordReceiveSignal() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-3");
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-3");

        orchestrator.onSignal(signalEvent(signal));

        verify(telegramChannel).sendSignal(signal);
        verify(discordChannel).sendSignal(signal);
    }

    @Test
    void onSignal_freshSignal_deliveryStateTransitionedToSent() {
        TradeSignal signal = freshSignal(TradingStyle.SWING);
        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-4");
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-4");

        orchestrator.onSignal(signalEvent(signal));

        assertThat(deliveryState.getOrCreate(signal.signalId(), "telegram").status())
                .isEqualTo(DeliveryStatus.SENT);
        assertThat(deliveryState.getOrCreate(signal.signalId(), "discord").status())
                .isEqualTo(DeliveryStatus.SENT);
    }

    // ── onSignal: stale signal dropped ────────────────────────────────────────

    @Test
    void onSignal_staleSignal_droppedNothingDispatched() {
        TradeSignal stale = staleSignal(TradingStyle.SCALP, 60_000); // 60s > 30s threshold

        orchestrator.onSignal(signalEvent(stale));

        verify(webSocketChannel, never()).sendSignal(any(), anyBoolean());
        verify(telegramChannel, never()).sendSignal(any());
        verify(discordChannel, never()).sendSignal(any());
    }

    @Test
    void onSignal_staleSignal_notAddedToHistoryBuffer() {
        TradeSignal stale = staleSignal(TradingStyle.INTRADAY, 120_000);

        orchestrator.onSignal(signalEvent(stale));

        assertThat(historyBuffer.getSignal(stale.signalId())).isEmpty();
    }

    // ── onSignal: channel failure isolation ───────────────────────────────────

    @Test
    void onSignal_telegramThrows_discordAndWebSocketStillReceiveSignal() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        doThrow(new RuntimeException("Telegram network error")).when(telegramChannel).sendSignal(signal);
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-5");

        orchestrator.onSignal(signalEvent(signal));

        verify(webSocketChannel).sendSignal(eq(signal), anyBoolean());
        verify(discordChannel).sendSignal(signal);
    }

    @Test
    void onSignal_discordThrows_telegramAndWebSocketStillReceiveSignal() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-6");
        doThrow(new RuntimeException("Discord rate limit")).when(discordChannel).sendSignal(signal);

        orchestrator.onSignal(signalEvent(signal));

        verify(webSocketChannel).sendSignal(eq(signal), anyBoolean());
        verify(telegramChannel).sendSignal(signal);
    }

    // ── onSignal: buffered enrichment applied on send ──────────────────────────

    @Test
    void onSignal_bufferedEnrichmentExists_telegramEditCalledImmediately() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        // Buffer enrichment before the signal is sent.
        deliveryState.bufferEnrichment(signal.signalId(), enrichment);

        when(telegramChannel.sendSignal(signal)).thenReturn("tg-msg-7");
        when(discordChannel.sendSignal(signal)).thenReturn("dc-msg-7");

        orchestrator.onSignal(signalEvent(signal));

        verify(telegramChannel).editWithEnrichment("tg-msg-7", signal, enrichment);
        assertThat(deliveryState.getOrCreate(signal.signalId(), "telegram").status())
                .isEqualTo(DeliveryStatus.ENRICHED);
    }

    // ── onEnrichment: WebSocket always receives enrichment ────────────────────

    @Test
    void onEnrichment_webSocketAlwaysReceivesEnrichment() {
        AiEnrichment enrichment = enrichmentFor("RELIANCE:INTRADAY:5m:1234:BUY");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        verify(webSocketChannel).sendEnrichment(enrichment);
    }

    @Test
    void onEnrichment_addedToHistoryBuffer() {
        AiEnrichment enrichment = enrichmentFor("RELIANCE:INTRADAY:5m:1234:BUY");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        assertThat(historyBuffer.getEnrichment(enrichment.signalId())).isPresent();
    }

    // ── onEnrichment: edit when SENT ──────────────────────────────────────────

    @Test
    void onEnrichment_telegramAlreadySent_editIsCalled() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        // Pre-populate history buffer and delivery state as if the signal was already sent.
        historyBuffer.addSignal(signal);
        deliveryState.getOrCreate(signal.signalId(), "telegram");
        deliveryState.transitionToSent(signal.signalId(), "telegram", "tg-sent-msg");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        verify(telegramChannel).editWithEnrichment("tg-sent-msg", signal, enrichment);
        assertThat(deliveryState.getOrCreate(signal.signalId(), "telegram").status())
                .isEqualTo(DeliveryStatus.ENRICHED);
    }

    @Test
    void onEnrichment_discordAlreadySent_editIsCalled() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        historyBuffer.addSignal(signal);
        deliveryState.getOrCreate(signal.signalId(), "discord");
        deliveryState.transitionToSent(signal.signalId(), "discord", "dc-sent-msg");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        verify(discordChannel).editWithEnrichment(
                eq("dc-sent-msg"), eq("ch-intraday-001"), eq(signal), eq(enrichment));
        assertThat(deliveryState.getOrCreate(signal.signalId(), "discord").status())
                .isEqualTo(DeliveryStatus.ENRICHED);
    }

    // ── onEnrichment: buffer when not yet SENT ────────────────────────────────

    @Test
    void onEnrichment_telegramNotYetSent_enrichmentIsBuffered() {
        TradeSignal signal = freshSignal(TradingStyle.SCALP);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        // Signal NOT yet sent — delivery entry is NEW.
        deliveryState.getOrCreate(signal.signalId(), "telegram");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        verify(telegramChannel, never()).editWithEnrichment(anyString(), any(TradeSignal.class), any(AiEnrichment.class));
        assertThat(deliveryState.getPendingEnrichment(signal.signalId())).isEqualTo(enrichment);
    }

    @Test
    void onEnrichment_discordNotYetSent_enrichmentIsBuffered() {
        TradeSignal signal = freshSignal(TradingStyle.SWING);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        deliveryState.getOrCreate(signal.signalId(), "discord");

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        verify(discordChannel, never()).editWithEnrichment(anyString(), anyString(), any(TradeSignal.class), any(AiEnrichment.class));
        assertThat(deliveryState.getPendingEnrichment(signal.signalId())).isEqualTo(enrichment);
    }

    // ── onEnrichment: channel failure isolation ───────────────────────────────

    @Test
    void onEnrichment_telegramEditThrows_discordAndWebSocketUnaffected() {
        TradeSignal signal = freshSignal(TradingStyle.INTRADAY);
        AiEnrichment enrichment = enrichmentFor(signal.signalId());

        historyBuffer.addSignal(signal);
        deliveryState.getOrCreate(signal.signalId(), "telegram");
        deliveryState.transitionToSent(signal.signalId(), "telegram", "tg-fail-msg");
        deliveryState.getOrCreate(signal.signalId(), "discord");
        deliveryState.transitionToSent(signal.signalId(), "discord", "dc-ok-msg");

        doThrow(new RuntimeException("Telegram edit failed"))
                .when(telegramChannel).editWithEnrichment(anyString(), any(TradeSignal.class), any(AiEnrichment.class));

        orchestrator.onEnrichment(enrichmentEvent(enrichment));

        // WebSocket still got the enrichment.
        verify(webSocketChannel).sendEnrichment(enrichment);
        // Discord still attempted edit despite Telegram failure.
        verify(discordChannel).editWithEnrichment(
                eq("dc-ok-msg"), eq("ch-intraday-001"), eq(signal), eq(enrichment));
    }

    // ── No optional channels wired ────────────────────────────────────────────

    @Test
    void onSignal_noOptionalChannels_webSocketStillReceivesSignal() {
        AlertOrchestrator wsOnly = new AlertOrchestrator(
                null, null, webSocketChannel, deliveryState, historyBuffer, alertConfig,
                new SimpleMeterRegistry());
        TradeSignal signal = freshSignal(TradingStyle.SWING);

        wsOnly.onSignal(signalEvent(signal));

        verify(webSocketChannel).sendSignal(eq(signal), anyBoolean());
    }

    @Test
    void onEnrichment_noOptionalChannels_webSocketStillReceivesEnrichment() {
        AlertOrchestrator wsOnly = new AlertOrchestrator(
                null, null, webSocketChannel, deliveryState, historyBuffer, alertConfig,
                new SimpleMeterRegistry());
        AiEnrichment enrichment = enrichmentFor("ANY:SIGNAL:ID");

        wsOnly.onEnrichment(enrichmentEvent(enrichment));

        verify(webSocketChannel).sendEnrichment(enrichment);
    }
}
