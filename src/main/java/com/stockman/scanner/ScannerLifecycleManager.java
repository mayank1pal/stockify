package com.stockman.scanner;

import com.stockman.config.ScannerConfig;
import com.stockman.scanner.service.AuthStateManager;
import com.stockman.scanner.service.ExchangeCalendar;
import com.stockman.scanner.service.FundamentalCacheService;
import com.stockman.scanner.service.InstrumentRegistry;
import com.stockman.scanner.service.TickerService;
import com.stockman.service.ZerodhaService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates scanner startup and shutdown.
 *
 * <p>On startup: validates the Zerodha session, loads instruments, triggers an async
 * fundamental-data refresh, and — if today is a trading day — connects the WebSocket
 * ticker. On shutdown: disconnects the ticker and persists the fundamental cache to disk.
 *
 * <p>Alert channels (Tasks 14–18) are intentionally absent here; they will be wired in
 * Task 22 (Demo Mode &amp; Final Integration).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScannerLifecycleManager {

    private final ScannerConfig config;
    private final ZerodhaService zerodhaService;
    private final InstrumentRegistry instrumentRegistry;
    private final ExchangeCalendar exchangeCalendar;
    private final TickerService tickerService;
    private final AuthStateManager authStateManager;
    private final FundamentalCacheService fundamentalCacheService;

    @PostConstruct
    public void onStartup() {
        if (!config.isEnabled()) {
            log.info("Scanner is disabled — skipping startup");
            return;
        }

        log.info("Scanner starting up...");

        // 1. Validate Zerodha session
        if (!authStateManager.validateSession(zerodhaService)) {
            log.info("No active Zerodha session — scanner in standby mode");
            return;
        }

        // 2. Load instruments from Zerodha
        instrumentRegistry.loadFromZerodha(zerodhaService);

        // 3. Refresh fundamentals asynchronously — don't block Spring context startup
        CompletableFuture.runAsync(() -> {
            try {
                fundamentalCacheService.refreshAll();
            } catch (Exception e) {
                log.error("Async fundamental refresh failed: {}", e.getMessage(), e);
            }
        });

        // 4. Check whether today is a trading day
        LocalDate today = LocalDate.now(exchangeCalendar.getTimeZone());
        LocalTime now = LocalTime.now(exchangeCalendar.getTimeZone());
        if (!exchangeCalendar.isTradingDay(today)) {
            log.info("Not a trading day ({}) — scanner in standby", today);
            return;
        }

        log.debug("Trading day confirmed: {} at {}", today, now);

        // 5. Subscribe to all registered instruments (holdings + watchlist consolidated)
        Set<Long> tokens = instrumentRegistry.getAllTokens();
        tickerService.start(tokens);
        log.info("Scanner started — subscribed to {} instruments", tokens.size());
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Scanner shutting down...");

        // 1. Disconnect WebSocket — TickerService handles its own thread-pool teardown
        try {
            tickerService.stop();
        } catch (Exception e) {
            log.warn("Error stopping TickerService during shutdown: {}", e.getMessage());
        }

        // 2. Persist fundamental cache so the next startup loads quickly
        try {
            fundamentalCacheService.persistToDisk();
        } catch (Exception e) {
            log.warn("Error persisting fundamental cache during shutdown: {}", e.getMessage());
        }

        log.info("Scanner shutdown complete");
    }
}
