package com.stockman.controller;

import com.stockman.scanner.event.ScanUniverseChangedEvent;
import com.stockman.scanner.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST endpoints for user watchlist and alert preferences.
 *
 * <p>Delegates persistence and validation to {@link WatchlistService}.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceController {

    private final ApplicationEventPublisher eventPublisher;
    private final WatchlistService watchlistService;

    @GetMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> getWatchlist() {
        List<String> symbols = new ArrayList<>(watchlistService.get());
        Collections.sort(symbols);

        Map<String, Object> body = Map.of(
                "symbols", symbols,
                "count", symbols.size(),
                "maxSize", WatchlistService.MAX_SYMBOLS
        );
        return ResponseEntity.ok(body);
    }

    @PutMapping("/watchlist")
    public ResponseEntity<?> updateWatchlist(@RequestBody Map<String, Object> body) {
        Object symbolsRaw = body.get("symbols");
        if (!(symbolsRaw instanceof List)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request body must contain a 'symbols' array"));
        }

        @SuppressWarnings("unchecked")
        List<Object> symbolsList = (List<Object>) symbolsRaw;

        // Collect and normalise input
        Set<String> requested = new HashSet<>(symbolsList.size());
        for (Object raw : symbolsList) {
            if (!(raw instanceof String s)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "All entries in 'symbols' must be strings"));
            }
            String upper = s.trim().toUpperCase();
            if (!upper.isBlank()) {
                requested.add(upper);
            }
        }

        // Delegate to WatchlistService for validation and persistence
        Set<String> updated;
        try {
            updated = watchlistService.update(requested);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }

        // Notify scanner of the updated universe
        Set<Long> tokens = watchlistService.getScanTokens();
        eventPublisher.publishEvent(new ScanUniverseChangedEvent(this, tokens));

        log.info("Watchlist updated: {} symbols, {} instrument tokens published",
                updated.size(), tokens.size());

        List<String> sortedSymbols = new ArrayList<>(updated);
        Collections.sort(sortedSymbols);

        return ResponseEntity.ok(Map.of(
                "symbols", sortedSymbols,
                "count", updated.size(),
                "updatedAt", Instant.now().toString()
        ));
    }
}
