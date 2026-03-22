package com.stockman.scanner.service;

import com.stockman.service.ZerodhaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class InstrumentRegistry {
    private final Map<String, Long> symbolToToken = new ConcurrentHashMap<>();
    private final Map<Long, String> tokenToSymbol = new ConcurrentHashMap<>();
    private final Map<String, String> symbolToIsin = new ConcurrentHashMap<>();
    private final Map<String, String> symbolToExchange = new ConcurrentHashMap<>();
    private final Map<String, Integer> symbolToLotSize = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToHigh52w = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToLow52w = new ConcurrentHashMap<>();

    public void register(String symbol, long token, String isin, String exchange, int lotSize) {
        symbolToToken.put(symbol, token);
        tokenToSymbol.put(token, symbol);
        symbolToIsin.put(symbol, isin);
        symbolToExchange.put(symbol, exchange);
        symbolToLotSize.put(symbol, lotSize);
    }

    /**
     * Load instruments from Zerodha's instrument list.
     * Call at startup and daily before market open.
     */
    public void loadFromZerodha(ZerodhaService zerodhaService) {
        try {
            var kite = zerodhaService.getActiveKiteConnect();
            if (kite == null) {
                log.warn("No active Kite session — skipping instrument download");
                return;
            }
            clear();
            var instruments = kite.getInstruments("NSE");
            for (var inst : instruments) {
                register(inst.tradingsymbol, inst.instrument_token,
                        "",
                        inst.exchange, inst.lot_size > 0 ? inst.lot_size : 1);
            }
            log.info("Loaded {} NSE instruments from Zerodha", size());
        } catch (Throwable e) {
            log.error("Failed to load instruments from Zerodha: {}", e.getMessage());
        }
    }

    public Long getToken(String symbol) { return symbolToToken.get(symbol); }
    public String getSymbol(long token) { return tokenToSymbol.get(token); }
    public String getIsin(String symbol) { return symbolToIsin.get(symbol); }
    public String getExchange(String symbol) { return symbolToExchange.get(symbol); }
    public Integer getLotSize(String symbol) { return symbolToLotSize.getOrDefault(symbol, 1); }
    public Double getHigh52w(String symbol) { return symbolToHigh52w.get(symbol); }
    public Double getLow52w(String symbol) { return symbolToLow52w.get(symbol); }
    public Set<Long> getAllTokens() { return Set.copyOf(tokenToSymbol.keySet()); }
    public boolean hasSymbol(String symbol) { return symbolToToken.containsKey(symbol); }
    public int size() { return symbolToToken.size(); }

    public void clear() {
        symbolToToken.clear(); tokenToSymbol.clear(); symbolToIsin.clear();
        symbolToExchange.clear(); symbolToLotSize.clear();
        symbolToHigh52w.clear(); symbolToLow52w.clear();
    }
}
