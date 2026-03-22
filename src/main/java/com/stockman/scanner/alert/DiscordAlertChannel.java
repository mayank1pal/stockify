package com.stockman.scanner.alert;

import com.stockman.config.AlertConfig;
import com.stockman.scanner.model.AiEnrichment;
import com.stockman.scanner.model.SignalEnums.SignalType;
import com.stockman.scanner.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.managers.AccountManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.EmbedBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.time.Instant;

@Component
@Slf4j
@ConditionalOnProperty(name = "alerts.discord.enabled", havingValue = "true")
public class DiscordAlertChannel {

    private JDA jda;
    private final AlertConfig.DiscordConfig discordConfig;

    public DiscordAlertChannel(AlertConfig alertConfig) {
        this.discordConfig = alertConfig.getDiscord();
        try {
            this.jda = JDABuilder.createDefault(discordConfig.getBotToken())
                    .disableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                    .setActivity(net.dv8tion.jda.api.entities.Activity.watching("market signals"))
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .build();
            jda.awaitReady();
            log.info("Discord bot initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize Discord bot: {}", e.getMessage());
        }
    }

    /**
     * Send a signal embed to the channel mapped to the signal's trading style.
     * Returns the Discord message ID, or null if the channel is not configured.
     */
    public String sendSignal(TradeSignal signal) {
        String channelId = discordConfig.getChannelIds().get(signal.style().name().toLowerCase());
        if (channelId == null) {
            log.debug("No Discord channel configured for style {}", signal.style());
            return null;
        }

        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            log.warn("Discord channel {} not found", channelId);
            return null;
        }

        MessageEmbed embed = buildSignalEmbed(signal);
        Message msg = channel.sendMessageEmbeds(embed).complete();
        log.debug("Discord signal sent for {} — messageId={}", signal.symbol(), msg.getId());
        return msg.getId();
    }

    /**
     * Edit a previously posted embed with AI enrichment.
     */
    public void editWithEnrichment(String messageId, String channelId,
                                   TradeSignal signal, AiEnrichment enrichment) {
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel != null) {
            MessageEmbed embed = buildSignalWithEnrichmentEmbed(signal, enrichment);
            channel.editMessageEmbedsById(messageId, embed).queue(
                    success -> log.debug("Discord embed updated messageId={}", messageId),
                    error -> log.warn("Discord embed edit failed messageId={}: {}", messageId, error.getMessage())
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
            log.info("Discord JDA shut down");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private MessageEmbed buildSignalEmbed(TradeSignal signal) {
        boolean isBuy = isBuy(signal);
        double riskReward = Math.abs(signal.target() - signal.entryPrice())
                / Math.abs(signal.entryPrice() - signal.stopLoss());

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(isBuy ? Color.GREEN : Color.RED)
                .setTitle(String.format("%s %s — %s (%s)",
                        isBuy ? "🟢" : "🔴",
                        signal.type().name().replace("_", " "),
                        signal.symbol(),
                        capitalize(signal.style().name())))
                .addField("Entry", String.format("₹%,.2f", signal.entryPrice()), true)
                .addField("Stop Loss", String.format("₹%,.2f", signal.stopLoss()), true)
                .addField("Target", String.format("₹%,.2f", signal.target()), true)
                .addField("R:R", String.format("%.2f", riskReward), true)
                .addField("Strength", signal.strength().name(), true)
                .setTimestamp(signal.generatedAt())
                .setFooter("StockMan Scanner");

        if (signal.triggerReasons() != null && !signal.triggerReasons().isEmpty()) {
            String triggers = String.join("\n• ", signal.triggerReasons());
            eb.addField("Triggers", "• " + triggers, false);
        }

        eb.addField("AI Analysis", "⏳ Pending…", false);
        return eb.build();
    }

    private MessageEmbed buildSignalWithEnrichmentEmbed(TradeSignal signal, AiEnrichment enrichment) {
        boolean isBuy = isBuy(signal);
        double riskReward = Math.abs(signal.target() - signal.entryPrice())
                / Math.abs(signal.entryPrice() - signal.stopLoss());

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(isBuy ? Color.GREEN : Color.RED)
                .setTitle(String.format("%s %s — %s (%s)",
                        isBuy ? "🟢" : "🔴",
                        signal.type().name().replace("_", " "),
                        signal.symbol(),
                        capitalize(signal.style().name())))
                .addField("Entry", String.format("₹%,.2f", signal.entryPrice()), true)
                .addField("Stop Loss", String.format("₹%,.2f", signal.stopLoss()), true)
                .addField("Target", String.format("₹%,.2f", signal.target()), true)
                .addField("R:R", String.format("%.2f", riskReward), true)
                .addField("Strength", signal.strength().name(), true)
                .addField("AI Confidence",
                        String.format("%.0f%%", enrichment.aiConfidence() * 100), true)
                .setTimestamp(signal.generatedAt())
                .setFooter("StockMan Scanner");

        if (signal.triggerReasons() != null && !signal.triggerReasons().isEmpty()) {
            String triggers = String.join("\n• ", signal.triggerReasons());
            eb.addField("Triggers", "• " + triggers, false);
        }

        if (enrichment.insight() != null && !enrichment.insight().isBlank()) {
            eb.addField("AI Insight", enrichment.insight(), false);
        }

        return eb.build();
    }

    private boolean isBuy(TradeSignal signal) {
        return signal.type() == SignalType.BUY || signal.type() == SignalType.STRONG_BUY;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
