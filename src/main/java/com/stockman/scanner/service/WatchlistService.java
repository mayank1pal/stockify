package com.stockman.scanner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persists the user watchlist as a JSON file at {@code data/watchlist.json}.
 *
 * <p>Writes are atomic: the new JSON is first written to a temp file in the same
 * directory, then atomically renamed over the target so readers never see a
 * partial file. The set is validated against {@link InstrumentRegistry}: unknown
 * symbols are rejected. Maximum 200 symbols.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WatchlistService {

    public static final String WATCHLIST_FILE = "data/watchlist.json";
    public static final int MAX_SYMBOLS = 200;

    private final InstrumentRegistry instrumentRegistry;
    private final ObjectMapper objectMapper;

    private volatile Set<String> symbols = ConcurrentHashMap.newKeySet();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void loadOnStartup() {
        loadFromDisk();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns an immutable snapshot of the current watchlist. */
    public Set<String> get() {
        return Collections.unmodifiableSet(symbols);
    }

    /**
     * Validates and replaces the watchlist.
     *
     * @param newSymbols the desired set of ticker symbols (uppercase)
     * @return the updated set after persistence
     * @throws IllegalArgumentException if any symbol is unknown or the list exceeds {@link #MAX_SYMBOLS}
     */
    public Set<String> update(Set<String> newSymbols) {
        if (newSymbols.size() > MAX_SYMBOLS) {
            throw new IllegalArgumentException(
                    "Watchlist cannot exceed " + MAX_SYMBOLS + " symbols; got " + newSymbols.size());
        }

        List<String> unknown = newSymbols.stream()
                .filter(s -> !instrumentRegistry.hasSymbol(s))
                .sorted()
                .toList();

        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown symbols not found in instrument registry: " + unknown);
        }

        Set<String> updated = ConcurrentHashMap.newKeySet(newSymbols.size());
        updated.addAll(newSymbols);

        this.symbols = updated;
        saveToDisk();

        log.info("Watchlist updated: {} symbols persisted to {}", updated.size(), WATCHLIST_FILE);
        return Collections.unmodifiableSet(updated);
    }

    /**
     * Converts the current watchlist symbols to their instrument tokens.
     * Symbols not present in the registry are silently skipped (registry may have
     * been cleared/reloaded since the watchlist was saved).
     */
    public Set<Long> getScanTokens() {
        return symbols.stream()
                .map(instrumentRegistry::getToken)
                .filter(t -> t != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    // ── Disk I/O ──────────────────────────────────────────────────────────────

    /** Reads the watchlist from disk. No-ops if the file is absent or unreadable. */
    void loadFromDisk() {
        File file = new File(WATCHLIST_FILE);
        if (!file.exists()) {
            log.info("Watchlist file not found at {} — starting with empty watchlist", WATCHLIST_FILE);
            return;
        }

        try {
            List<String> loaded = objectMapper.readValue(file, new TypeReference<List<String>>() {});
            Set<String> newSet = ConcurrentHashMap.newKeySet(loaded.size());
            newSet.addAll(loaded);
            this.symbols = newSet;
            log.info("Loaded {} watchlist symbols from {}", loaded.size(), WATCHLIST_FILE);
        } catch (IOException e) {
            log.error("Failed to load watchlist from {}: {}", WATCHLIST_FILE, e.getMessage());
        }
    }

    /** Writes the current watchlist to disk atomically (temp-file + rename). */
    void saveToDisk() {
        try {
            Path target = Paths.get(WATCHLIST_FILE);
            // Ensure parent directory exists
            Files.createDirectories(target.getParent());

            // Write to a sibling temp file first
            Path tmp = target.getParent().resolve("watchlist.tmp");
            List<String> sorted = new ArrayList<>(symbols);
            Collections.sort(sorted);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sorted);

            // Atomic rename
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Watchlist saved to {}", WATCHLIST_FILE);
        } catch (IOException e) {
            log.error("Failed to save watchlist to {}: {}", WATCHLIST_FILE, e.getMessage());
        }
    }
}
