package com.stockman.controller;

import com.stockman.scanner.event.ScanUniverseChangedEvent;
import com.stockman.scanner.service.InstrumentRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST endpoints for user watchlist and alert preferences.
 *
 * <p>Uses an in-memory watchlist store as a temporary placeholder until
 * Task 21 (WatchlistService) is implemented.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceController {

    private static final int MAX_WATCHLIST_SIZE = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final InstrumentRegistry instrumentRegistry;

    /**
     * Temporary in-memory watchlist. Task 21 will replace this with WatchlistService.
     * Static so state persists across request-scoped bean recreation.
     */
    private static final Set<String> watchlistSymbols =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @GetMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> getWatchlist() {
        List<String> symbols = new ArrayList<>(watchlistSymbols);
        Collections.sort(symbols);

        Map<String, Object> body = Map.of(
                "symbols", symbols,
                "count", symbols.size(),
                "maxSize", MAX_WATCHLIST_SIZE
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

        if (symbolsList.size() > MAX_WATCHLIST_SIZE) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Watchlist cannot exceed " + MAX_WATCHLIST_SIZE + " symbols"));
        }

        // Validate and collect symbols
        List<String> validated = new ArrayList<>(symbolsList.size());
        List<String> unknown = new ArrayList<>();
        for (Object raw : symbolsList) {
            if (!(raw instanceof String symbol)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "All entries in 'symbols' must be strings"));
            }
            String upper = symbol.trim().toUpperCase();
            if (upper.isBlank()) continue;
            if (!instrumentRegistry.hasSymbol(upper)) {
                unknown.add(upper);
            } else {
                validated.add(upper);
            }
        }

        if (!unknown.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", "Unknown symbols not found in instrument registry",
                            "unknownSymbols", unknown
                    ));
        }

        // Compute token set for the new universe
        Set<Long> tokens = ConcurrentHashMap.newKeySet(validated.size());
        for (String sym : validated) {
            Long token = instrumentRegistry.getToken(sym);
            if (token != null) {
                tokens.add(token);
            }
        }

        // Update in-memory store
        watchlistSymbols.clear();
        watchlistSymbols.addAll(validated);

        // Notify scanner of the updated universe
        eventPublisher.publishEvent(new ScanUniverseChangedEvent(this, tokens));

        log.info("Watchlist updated: {} symbols, {} instrument tokens published",
                validated.size(), tokens.size());

        return ResponseEntity.ok(Map.of(
                "symbols", new ArrayList<>(watchlistSymbols),
                "count", watchlistSymbols.size(),
                "updatedAt", Instant.now().toString()
        ));
    }
}
