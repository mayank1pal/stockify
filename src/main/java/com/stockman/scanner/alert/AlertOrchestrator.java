package com.stockman.scanner.alert;

import com.stockman.config.AlertConfig;
import com.stockman.scanner.event.AiEnrichmentEvent;
import com.stockman.scanner.event.SignalEvent;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.DeliveryStatus;
import com.stockman.scanner.model.TradeSignal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Listens to {@link SignalEvent} and {@link AiEnrichmentEvent} and routes each to all
 * enabled alert channels (WebSocket always; Telegram and Discord conditionally).
 *
 * <p>Staleness check: signals older than the per-style threshold are silently dropped.
 * Channel failures are caught and logged so they never block sibling channels.
 * AI enrichments that arrive before the initial message is sent are buffered and applied
 * when the send completes.
 */
@Component
@Slf4j
public class AlertOrchestrator {

    private final Optional<TelegramAlertChannel> telegram;
    private final Optional<DiscordAlertChannel> discord;
    private final WebSocketAlertChannel webSocket;
    private final AlertDeliveryState deliveryState;
    private final SignalHistoryBuffer historyBuffer;
    private final AlertConfig alertConfig;
    private final MeterRegistry meterRegistry;

    // Cached counters for the non-tagged "dropped" case
    private final Counter alertsDropped;

    // Use @Autowired(required = false) so Telegram/Discord beans are absent when disabled.
    public AlertOrchestrator(
            @Autowired(required = false) TelegramAlertChannel telegram,
            @Autowired(required = false) DiscordAlertChannel discord,
            WebSocketAlertChannel webSocket,
            AlertDeliveryState deliveryState,
            SignalHistoryBuffer historyBuffer,
            AlertConfig alertConfig,
            MeterRegistry meterRegistry) {
        this.telegram = Optional.ofNullable(telegram);
        this.discord = Optional.ofNullable(discord);
        this.webSocket = webSocket;
        this.deliveryState = deliveryState;
        this.historyBuffer = historyBuffer;
        this.alertConfig = alertConfig;
        this.meterRegistry = meterRegistry;
        this.alertsDropped = meterRegistry.counter("scanner.alerts.dropped");
    }

    // ── Signal handling ────────────────────────────────────────────────────────

    @EventListener
    @Async("alertExecutor")
    public void onSignal(SignalEvent event) {
        TradeSignal signal = event.getSignal();

        if (isStale(signal)) {
            log.debug("Dropping stale signal: {}", signal.signalId());
            alertsDropped.increment();
            return;
        }

        historyBuffer.addSignal(signal);

        // WebSocket is always dispatched first (non-optional).
        webSocket.sendSignal(signal, event.isAiPending());
        meterRegistry.counter("scanner.alerts.sent", "channel", "websocket").increment();

        // Telegram: fire-and-forget; check for a buffered enrichment after send.
        telegram.ifPresent(t -> {
            try {
                // Pre-create the entry so a racing enrichment can observe it.
                deliveryState.getOrCreate(signal.signalId(), "telegram");

                String msgId = t.sendSignal(signal);
                deliveryState.transitionToSent(signal.signalId(), "telegram", msgId);
                meterRegistry.counter("scanner.alerts.sent", "channel", "telegram").increment();

                // If enrichment already arrived while we were sending, apply it immediately.
                AiEnrichment pending = deliveryState.getPendingEnrichment(signal.signalId());
                if (pending != null) {
                    t.editWithEnrichment(msgId, signal, pending);
                    deliveryState.transitionToEnriched(signal.signalId(), "telegram");
                }
            } catch (Exception e) {
                log.warn("Telegram alert failed for {}: {}", signal.signalId(), e.getMessage());
                meterRegistry.counter("scanner.alerts.failed", "channel", "telegram").increment();
            }
        });

        // Discord: fire-and-forget.
        discord.ifPresent(d -> {
            try {
                deliveryState.getOrCreate(signal.signalId(), "discord");

                String msgId = d.sendSignal(signal);
                if (msgId != null) {
                    deliveryState.transitionToSent(signal.signalId(), "discord", msgId);
                    meterRegistry.counter("scanner.alerts.sent", "channel", "discord").increment();

                    AiEnrichment pending = deliveryState.getPendingEnrichment(signal.signalId());
                    if (pending != null) {
                        String channelId = resolveDiscordChannelId(signal);
                        if (channelId != null) {
                            d.editWithEnrichment(msgId, channelId, signal, pending);
                            deliveryState.transitionToEnriched(signal.signalId(), "discord");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Discord alert failed for {}: {}", signal.signalId(), e.getMessage());
                meterRegistry.counter("scanner.alerts.failed", "channel", "discord").increment();
            }
        });
    }

    // ── Enrichment handling ────────────────────────────────────────────────────

    @EventListener
    @Async("alertExecutor")
    public void onEnrichment(AiEnrichmentEvent event) {
        AiEnrichment enrichment = event.getEnrichment();

        historyBuffer.addEnrichment(enrichment);

        // WebSocket: always broadcast immediately.
        webSocket.sendEnrichment(enrichment);

        // Telegram: edit the already-sent message, or buffer if not yet sent.
        telegram.ifPresent(t -> {
            var entry = deliveryState.getOrCreate(enrichment.signalId(), "telegram");
            if (entry.status() == DeliveryStatus.SENT) {
                try {
                    historyBuffer.getSignal(enrichment.signalId()).ifPresent(signal -> {
                        t.editWithEnrichment(entry.messageRef(), signal, enrichment);
                        deliveryState.transitionToEnriched(enrichment.signalId(), "telegram");
                    });
                } catch (Exception e) {
                    log.warn("Telegram enrichment edit failed for {}: {}", enrichment.signalId(), e.getMessage());
                }
            } else {
                deliveryState.bufferEnrichment(enrichment.signalId(), enrichment);
            }
        });

        // Discord: edit the already-sent embed, or buffer if not yet sent.
        discord.ifPresent(d -> {
            var entry = deliveryState.getOrCreate(enrichment.signalId(), "discord");
            if (entry.status() == DeliveryStatus.SENT) {
                try {
                    historyBuffer.getSignal(enrichment.signalId()).ifPresent(signal -> {
                        String channelId = resolveDiscordChannelId(signal);
                        if (channelId != null) {
                            d.editWithEnrichment(entry.messageRef(), channelId, signal, enrichment);
                            deliveryState.transitionToEnriched(enrichment.signalId(), "discord");
                        }
                    });
                } catch (Exception e) {
                    log.warn("Discord enrichment edit failed for {}: {}", enrichment.signalId(), e.getMessage());
                }
            } else {
                deliveryState.bufferEnrichment(enrichment.signalId(), enrichment);
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true when the signal has aged beyond the acceptable window for its style:
     * SCALP → 30 s, INTRADAY → 60 s, SWING → 300 s.
     */
    boolean isStale(TradeSignal signal) {
        long ageMs = Duration.between(signal.generatedAt(), Instant.now()).toMillis();
        return switch (signal.style()) {
            case SCALP -> ageMs > 30_000;
            case INTRADAY -> ageMs > 60_000;
            case SWING -> ageMs > 300_000;
        };
    }

    /**
     * Looks up the Discord channel ID for the signal's {@link com.stockman.scanner.model.SignalEnums.TradingStyle}.
     * Returns null if no channel is configured for the style.
     */
    private String resolveDiscordChannelId(TradeSignal signal) {
        return alertConfig.getDiscord().getChannelIds()
                .get(signal.style().name().toLowerCase());
    }
}
