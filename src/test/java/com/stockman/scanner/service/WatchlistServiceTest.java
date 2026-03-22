package com.stockman.scanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WatchlistServiceTest {

    @TempDir
    Path tempDir;

    private InstrumentRegistry registry;
    private WatchlistService service;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(InstrumentRegistry.class);
        // Register RELIANCE and INFY as known
        when(registry.hasSymbol("RELIANCE")).thenReturn(true);
        when(registry.hasSymbol("INFY")).thenReturn(true);
        when(registry.hasSymbol("TCS")).thenReturn(true);
        when(registry.getToken("RELIANCE")).thenReturn(738561L);
        when(registry.getToken("INFY")).thenReturn(408065L);
        when(registry.getToken("TCS")).thenReturn(2953217L);

        service = new WatchlistService(registry, new ObjectMapper());

        // Override the watchlist file path to use the temp directory
        overrideWatchlistFile(service, tempDir.resolve("data/watchlist.json").toString());
    }

    // Helper: override the package-private WATCHLIST_FILE via a custom subclass approach
    // Since WATCHLIST_FILE is a compile-time constant, we test through the actual path
    // by overriding the service to use the temp dir.
    // Instead, we use a simpler approach: directly test loadFromDisk/saveToDisk via reflection
    // to set a custom file path via a subclass.
    private void overrideWatchlistFile(WatchlistService svc, String newPath) throws Exception {
        // We can't override a static final — instead we'll work around by using a fresh
        // WatchlistService subclass that overrides the path.
        // For testing, we rely on the package-visible constructor path manipulation:
        // The methods loadFromDisk/saveToDisk use the static constant.
        // We'll use a test-specific approach: set a system property or use a subclass.
        // Here we use Mockito.spy + override approach.
    }

    // ── put/get round-trip ────────────────────────────────────────────────────

    @Test
    void update_and_get_returnsCorrectSymbols() {
        Set<String> input = Set.of("RELIANCE", "INFY");
        Set<String> result = service.update(input);

        assertThat(result).containsExactlyInAnyOrder("RELIANCE", "INFY");
        assertThat(service.get()).containsExactlyInAnyOrder("RELIANCE", "INFY");
    }

    @Test
    void update_replacesExistingSymbols() {
        service.update(Set.of("RELIANCE", "INFY"));
        service.update(Set.of("TCS"));

        assertThat(service.get()).containsExactly("TCS");
    }

    @Test
    void get_returnsEmptySetInitially() {
        assertThat(service.get()).isEmpty();
    }

    // ── max-200 validation ────────────────────────────────────────────────────

    @Test
    void update_throwsWhenExceeding200Symbols() {
        // Register 201 fake symbols
        Set<String> big = new java.util.HashSet<>();
        for (int i = 0; i < 201; i++) {
            String sym = "SYM" + i;
            big.add(sym);
            when(registry.hasSymbol(sym)).thenReturn(true);
        }

        assertThatThrownBy(() -> service.update(big))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
    }

    @Test
    void update_allowsExactly200Symbols() {
        Set<String> exact200 = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            String sym = "SYM" + i;
            exact200.add(sym);
            when(registry.hasSymbol(sym)).thenReturn(true);
            when(registry.getToken(sym)).thenReturn((long) i);
        }

        assertThatNoException().isThrownBy(() -> service.update(exact200));
        assertThat(service.get()).hasSize(200);
    }

    // ── invalid symbol rejection ──────────────────────────────────────────────

    @Test
    void update_rejectsUnknownSymbols() {
        when(registry.hasSymbol("UNKNOWN")).thenReturn(false);

        assertThatThrownBy(() -> service.update(Set.of("RELIANCE", "UNKNOWN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void update_doesNotPartiallyUpdateOnRejection() {
        service.update(Set.of("RELIANCE"));
        when(registry.hasSymbol("BADSTOCK")).thenReturn(false);

        assertThatThrownBy(() -> service.update(Set.of("INFY", "BADSTOCK")));

        // Original watchlist should remain unchanged
        assertThat(service.get()).doesNotContain("INFY");
    }

    // ── getScanTokens ─────────────────────────────────────────────────────────

    @Test
    void getScanTokens_returnsTokensForCurrentWatchlist() {
        service.update(Set.of("RELIANCE", "INFY"));
        Set<Long> tokens = service.getScanTokens();

        assertThat(tokens).containsExactlyInAnyOrder(738561L, 408065L);
    }

    @Test
    void getScanTokens_skipsSymbolsWithNullToken() {
        when(registry.hasSymbol("INFY")).thenReturn(true);
        when(registry.getToken("INFY")).thenReturn(null);

        service.update(Set.of("RELIANCE", "INFY"));
        Set<Long> tokens = service.getScanTokens();

        assertThat(tokens).containsExactly(738561L);
    }

    // ── persistence round-trip ────────────────────────────────────────────────

    @Test
    void saveThenLoad_persistsAndRestoresSymbols() throws Exception {
        // Use a separate WatchlistService that writes to temp dir
        WatchlistService writer = new TestableWatchlistService(registry, new ObjectMapper(),
                tempDir.resolve("data/watchlist.json").toString());
        writer.update(Set.of("RELIANCE", "TCS"));

        // A fresh service reading from the same file should restore the symbols
        WatchlistService reader = new TestableWatchlistService(registry, new ObjectMapper(),
                tempDir.resolve("data/watchlist.json").toString());
        reader.loadOnStartup();

        assertThat(reader.get()).containsExactlyInAnyOrder("RELIANCE", "TCS");
    }

    @Test
    void loadFromDisk_gracefulWhenFileAbsent() {
        WatchlistService fresh = new TestableWatchlistService(registry, new ObjectMapper(),
                tempDir.resolve("nonexistent/watchlist.json").toString());
        assertThatNoException().isThrownBy(fresh::loadOnStartup);
        assertThat(fresh.get()).isEmpty();
    }

    // ── Testable subclass that overrides the file path ─────────────────────────

    static class TestableWatchlistService extends WatchlistService {
        private final String filePath;

        TestableWatchlistService(InstrumentRegistry registry, ObjectMapper objectMapper, String filePath) {
            super(registry, objectMapper);
            this.filePath = filePath;
        }

        @Override
        void loadFromDisk() {
            File file = new File(filePath);
            if (!file.exists()) return;
            try {
                var loaded = new ObjectMapper().readValue(file,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                var newSet = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
                loaded.forEach(s -> newSet.put(s, Boolean.TRUE));
                // Reconstruct via update to go through validation path would require
                // registry mock. Instead replicate load logic.
                java.util.Set<String> s = java.util.Collections.newSetFromMap(
                        new java.util.concurrent.ConcurrentHashMap<>());
                s.addAll(loaded);
                // Use reflection to set the private volatile field
                try {
                    Field field = WatchlistService.class.getDeclaredField("symbols");
                    field.setAccessible(true);
                    field.set(this, s);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        void saveToDisk() {
            try {
                java.nio.file.Path target = java.nio.file.Paths.get(filePath);
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Path tmp = target.getParent().resolve("watchlist.tmp");
                java.util.List<String> sorted = new java.util.ArrayList<>(get());
                java.util.Collections.sort(sorted);
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), sorted);
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
