package com.stockman.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "alerts")
@Validated
@Getter @Setter
public class AlertConfig {
    private TelegramConfig telegram = new TelegramConfig();
    private DiscordConfig discord = new DiscordConfig();
    private WebSocketAlertConfig websocket = new WebSocketAlertConfig();

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (telegram.isEnabled() && (telegram.getBotToken() == null || telegram.getBotToken().isBlank())) {
            throw new IllegalStateException("alerts.telegram.enabled=true but bot-token is blank");
        }
        if (discord.isEnabled() && (discord.getBotToken() == null || discord.getBotToken().isBlank())) {
            throw new IllegalStateException("alerts.discord.enabled=true but bot-token is blank");
        }
    }

    @Getter @Setter
    public static class TelegramConfig {
        private boolean enabled = false;
        private String botToken = "";
        private String chatId = "";
    }

    @Getter @Setter
    public static class DiscordConfig {
        private boolean enabled = false;
        private String botToken = "";
        private Map<String, String> channelIds = new java.util.HashMap<>();
    }

    @Getter @Setter
    public static class WebSocketAlertConfig {
        private boolean enabled = true;
    }
}
