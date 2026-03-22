package com.stockman.scanner.alert;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import com.stockman.config.AlertConfig;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(name = "alerts.telegram.enabled", havingValue = "true")
public class TelegramAlertChannel {

    private final TelegramBot bot;
    private final String chatId;

    public TelegramAlertChannel(AlertConfig alertConfig) {
        this.bot = new TelegramBot(alertConfig.getTelegram().getBotToken());
        this.chatId = alertConfig.getTelegram().getChatId();
    }

    /**
     * Send a new signal alert. Returns the Telegram message ID so the caller
     * can later edit it with AI enrichment.
     */
    public String sendSignal(TradeSignal signal) {
        String html = formatSignalHtml(signal);
        SendMessage request = new SendMessage(chatId, html).parseMode(ParseMode.HTML);
        SendResponse response = bot.execute(request);
        if (response.isOk()) {
            String messageId = String.valueOf(response.message().messageId());
            log.debug("Telegram signal sent for {} — messageId={}", signal.symbol(), messageId);
            return messageId;
        }
        throw new RuntimeException("Telegram send failed: " + response.description());
    }

    /**
     * Edit a previously sent signal message to include AI enrichment.
     */
    public void editWithEnrichment(String messageId, TradeSignal signal, AiEnrichment enrichment) {
        String html = formatSignalWithEnrichmentHtml(signal, enrichment);
        EditMessageText edit = new EditMessageText(chatId, Integer.parseInt(messageId), html)
                .parseMode(ParseMode.HTML);
        var response = bot.execute(edit);
        if (!response.isOk()) {
            log.warn("Telegram edit failed for messageId={}: {}", messageId, response.description());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String formatSignalHtml(TradeSignal signal) {
        String emoji = isBuy(signal) ? "🟢" : "🔴";
        String strengthLabel = signal.strength().name().replace("_", " ");
        String typeLabel = signal.type().name().replace("_", " ");
        String styleLabel = capitalize(signal.style().name());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>%s %s</b> — <b>%s</b> (%s)\n",
                emoji, strengthLabel, typeLabel, signal.symbol(), styleLabel));
        sb.append(String.format("Entry: <b>₹%,.2f</b> | Stop: ₹%,.2f | Target: ₹%,.2f\n",
                signal.entryPrice(), signal.stopLoss(), signal.target()));

        double riskReward = Math.abs(signal.target() - signal.entryPrice())
                / Math.abs(signal.entryPrice() - signal.stopLoss());
        sb.append(String.format("R:R  <b>%.2f</b>\n", riskReward));

        if (signal.triggerReasons() != null && !signal.triggerReasons().isEmpty()) {
            sb.append("Triggers:\n");
            for (String reason : signal.triggerReasons()) {
                sb.append("  • ").append(reason).append("\n");
            }
        }

        sb.append("\n⏳ <i>AI analysis pending…</i>");
        return sb.toString();
    }

    private String formatSignalWithEnrichmentHtml(TradeSignal signal, AiEnrichment enrichment) {
        String emoji = isBuy(signal) ? "🟢" : "🔴";
        String strengthLabel = signal.strength().name().replace("_", " ");
        String typeLabel = signal.type().name().replace("_", " ");
        String styleLabel = capitalize(signal.style().name());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>%s %s</b> — <b>%s</b> (%s)\n",
                emoji, strengthLabel, typeLabel, signal.symbol(), styleLabel));
        sb.append(String.format("Entry: <b>₹%,.2f</b> | Stop: ₹%,.2f | Target: ₹%,.2f\n",
                signal.entryPrice(), signal.stopLoss(), signal.target()));

        double riskReward = Math.abs(signal.target() - signal.entryPrice())
                / Math.abs(signal.entryPrice() - signal.stopLoss());
        sb.append(String.format("R:R  <b>%.2f</b>\n", riskReward));

        if (signal.triggerReasons() != null && !signal.triggerReasons().isEmpty()) {
            sb.append("Triggers:\n");
            for (String reason : signal.triggerReasons()) {
                sb.append("  • ").append(reason).append("\n");
            }
        }

        sb.append(String.format("\n🤖 <b>AI Confidence: %.0f%%</b>\n", enrichment.aiConfidence() * 100));
        sb.append("<i>").append(enrichment.insight()).append("</i>");
        return sb.toString();
    }

    private boolean isBuy(TradeSignal signal) {
        return signal.type() == SignalType.BUY || signal.type() == SignalType.STRONG_BUY;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
