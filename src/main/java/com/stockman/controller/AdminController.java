package com.stockman.controller;

import com.stockman.config.AdminConfig;
import com.stockman.config.AlertConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AdminConfig adminConfig;
    private final AlertConfig alertConfig;

    // ── Authorization ─────────────────────────────────────────────────────────

    private boolean isAuthorized(String adminKey) {
        return adminConfig.getCredential() != null &&
               adminConfig.getCredential().equals(adminKey);
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    @GetMapping("/alerts/config")
    public ResponseEntity<?> getAlertConfig(
            @RequestHeader("X-Admin-Key") String adminKey) {
        if (!isAuthorized(adminKey)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }
        return ResponseEntity.ok(buildMaskedConfig());
    }

    @PutMapping("/alerts/config")
    public ResponseEntity<?> updateAlertConfig(
            @RequestHeader("X-Admin-Key") String adminKey,
            @RequestBody Map<String, Object> body) {
        if (!isAuthorized(adminKey)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Apply writable preferences (not tokens — those stay in env vars)
        applyPreferences(body);

        log.info("Admin alert config updated by authorized user");
        return ResponseEntity.ok(buildMaskedConfig());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the current alert config with sensitive tokens masked as {@code "***"}.
     */
    private Map<String, Object> buildMaskedConfig() {
        Map<String, Object> result = new HashMap<>();

        // Telegram
        AlertConfig.TelegramConfig telegram = alertConfig.getTelegram();
        Map<String, Object> telegramMap = new HashMap<>();
        telegramMap.put("enabled", telegram.isEnabled());
        telegramMap.put("botToken", maskSecret(telegram.getBotToken()));
        telegramMap.put("chatId", telegram.getChatId());
        result.put("telegram", telegramMap);

        // Discord
        AlertConfig.DiscordConfig discord = alertConfig.getDiscord();
        Map<String, Object> discordMap = new HashMap<>();
        discordMap.put("enabled", discord.isEnabled());
        discordMap.put("botToken", maskSecret(discord.getBotToken()));
        discordMap.put("channelIds", discord.getChannelIds());
        result.put("discord", discordMap);

        // WebSocket
        AlertConfig.WebSocketAlertConfig ws = alertConfig.getWebsocket();
        Map<String, Object> wsMap = new HashMap<>();
        wsMap.put("enabled", ws.isEnabled());
        result.put("websocket", wsMap);

        return result;
    }

    /**
     * Applies non-secret preference fields from the request body.
     * Bot tokens are intentionally excluded — those are managed via env vars only.
     */
    private void applyPreferences(Map<String, Object> body) {
        if (body.containsKey("telegram")) {
            Object telegramRaw = body.get("telegram");
            if (telegramRaw instanceof Map<?, ?> telegramMap) {
                if (telegramMap.containsKey("enabled")) {
                    alertConfig.getTelegram().setEnabled(
                            Boolean.TRUE.equals(telegramMap.get("enabled")));
                }
                if (telegramMap.containsKey("chatId")) {
                    Object chatId = telegramMap.get("chatId");
                    if (chatId instanceof String s) {
                        alertConfig.getTelegram().setChatId(s);
                    }
                }
            }
        }

        if (body.containsKey("discord")) {
            Object discordRaw = body.get("discord");
            if (discordRaw instanceof Map<?, ?> discordMap) {
                if (discordMap.containsKey("enabled")) {
                    alertConfig.getDiscord().setEnabled(
                            Boolean.TRUE.equals(discordMap.get("enabled")));
                }
            }
        }

        if (body.containsKey("websocket")) {
            Object wsRaw = body.get("websocket");
            if (wsRaw instanceof Map<?, ?> wsMap) {
                if (wsMap.containsKey("enabled")) {
                    alertConfig.getWebsocket().setEnabled(
                            Boolean.TRUE.equals(wsMap.get("enabled")));
                }
            }
        }
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) return "";
        return "***";
    }
}
