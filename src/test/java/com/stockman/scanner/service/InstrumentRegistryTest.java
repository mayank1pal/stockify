package com.stockman.scanner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstrumentRegistryTest {
    private InstrumentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InstrumentRegistry();
        registry.register("RELIANCE", 738561L, "INE002A01018", "NSE", 1);
        registry.register("TCS", 2953217L, "INE467B01029", "NSE", 1);
    }

    @Test
    void getToken_validSymbol_returnsToken() {
        assertEquals(738561L, registry.getToken("RELIANCE"));
    }

    @Test
    void getToken_invalidSymbol_returnsNull() {
        assertNull(registry.getToken("INVALID"));
    }

    @Test
    void getSymbol_validToken_returnsSymbol() {
        assertEquals("RELIANCE", registry.getSymbol(738561L));
    }

    @Test
    void getAllTokens_returnsRegisteredTokens() {
        var tokens = registry.getAllTokens();
        assertEquals(2, tokens.size());
        assertTrue(tokens.contains(738561L));
    }

    @Test
    void getLotSize_returnsCorrectSize() {
        assertEquals(1, registry.getLotSize("RELIANCE"));
    }

    @Test
    void hasSymbol_registered_returnsTrue() {
        assertTrue(registry.hasSymbol("RELIANCE"));
    }

    @Test
    void hasSymbol_notRegistered_returnsFalse() {
        assertFalse(registry.hasSymbol("INVALID"));
    }

    @Test
    void clear_removesAllData() {
        registry.clear();
        assertEquals(0, registry.size());
        assertNull(registry.getToken("RELIANCE"));
    }
}
