package com.stockman.scanner.alert;

import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class WebSocketAlertChannel {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast a new signal to all subscribed clients.
     *
     * @param signal    the trade signal
     * @param aiPending true if AI enrichment has not yet been applied
     */
    public void sendSignal(TradeSignal signal, boolean aiPending) {
        Map<String, Object> payload = Map.of(
                "signal", signal,
                "aiPending", aiPending,
                "type", "SIGNAL"
        );
        messagingTemplate.convertAndSend("/topic/signals", payload);
        log.debug("WebSocket signal sent for {}", signal.symbol());
    }

    /**
     * Broadcast AI enrichment results to all subscribed clients.
     */
    public void sendEnrichment(AiEnrichment enrichment) {
        messagingTemplate.convertAndSend("/topic/ai-enrichment", enrichment);
        log.debug("WebSocket enrichment sent for signalId={}", enrichment.signalId());
    }

    /**
     * Broadcast scanner status updates (e.g. active symbols, health).
     */
    public void sendStatus(Map<String, Object> status) {
        messagingTemplate.convertAndSend("/topic/scanner-status", status);
        log.debug("WebSocket status update sent");
    }
}
