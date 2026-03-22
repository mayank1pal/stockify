package com.stockman.scanner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.util.concurrent.RateLimiter;
import com.stockman.scanner.model.FundamentalData;
import com.stockman.scanner.model.SignalEnums.DataQuality;
import com.stockman.service.FinnhubService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class FundamentalCacheService {

    private final ConcurrentHashMap<String, FundamentalData> cache = new ConcurrentHashMap<>();
    private final FinnhubService finnhubService;
    private final InstrumentRegistry instrumentRegistry;
    private final ExchangeCalendar exchangeCalendar;
    private final ObjectMapper objectMapper;

    /** Mutable so tests can redirect to a temp directory via reflection. */
    private String cacheFilePath = "data/fundamental-cache.json";
    private static final int MAX_STALENESS_DAYS = 7;

    // ~0.8 permits/sec = 48/min, safely under Finnhub's 60/min free-tier limit
    private final RateLimiter rateLimiter = RateLimiter.create(0.8);

    public FundamentalCacheService(FinnhubService finnhubService,
                                   InstrumentRegistry instrumentRegistry,
                                   ExchangeCalendar exchangeCalendar) {
        this.finnhubService = finnhubService;
        this.instrumentRegistry = instrumentRegistry;
        this.exchangeCalendar = exchangeCalendar;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    /** Daily refresh at 7:00 AM IST, skip weekends/holidays */
    @Scheduled(cron = "0 0 7 * * ?", zone = "Asia/Kolkata")
    public void scheduledRefresh() {
        LocalDate today = LocalDate.now(exchangeCalendar.getTimeZone());
        if (!exchangeCalendar.isTradingDay(today)) {
            log.info("Skipping fundamental refresh — not a trading day");
            return;
        }
        refreshAll();
    }

    /**
     * Returns cached data for the given symbol, or null if not present.
     * If the data is older than MAX_STALENESS_DAYS, returns the entry with
     * DataQuality.STALE so callers can decide whether to use it.
     */
    public FundamentalData get(String symbol) {
        FundamentalData data = cache.get(symbol);
        if (data == null) {
            return null;
        }
        if (isStale(data)) {
            return new FundamentalData(
                data.symbol(), data.instrumentToken(), data.isin(),
                data.exchange(), data.sector(), data.lotSize(),
                data.pe(), data.eps(), data.marketCap(),
                data.high52w(), data.low52w(),
                data.dividendYield(), data.bookValue(),
                data.roe(), data.debtToEquity(),
                data.lastUpdated(), DataQuality.STALE
            );
        }
        return data;
    }

    /** Directly put an entry into the cache (used by tests and bulk loaders). */
    public void put(String symbol, FundamentalData data) {
        cache.put(symbol, data);
    }

    /** Return all cached entries (may include stale ones with original quality). */
    public Collection<FundamentalData> getAll() {
        return cache.values();
    }

    public int size() {
        return cache.size();
    }

    /**
     * Refresh all registered symbols from Finnhub (best-effort).
     * Rate-limited to ~48 calls/min. On failure, the existing cache entry is
     * kept unchanged. Persists to disk after the sweep.
     */
    public void refreshAll() {
        int updated = 0;
        int failed = 0;

        for (String symbol : instrumentRegistry.getAllTokens().stream()
                .map(instrumentRegistry::getSymbol)
                .filter(s -> s != null)
                .toList()) {

            try {
                rateLimiter.acquire();
                FundamentalData fetched = fetchFromFinnhub(symbol);
                cache.put(symbol, fetched);
                updated++;
            } catch (Exception e) {
                log.debug("Finnhub fetch failed for {}: {}", symbol, e.getMessage());
                failed++;
            }
        }

        log.info("Fundamental refresh complete — updated={}, failed={}", updated, failed);
        persistToDisk();
    }

    // -------------------------------------------------------------------------
    // Finnhub fetch (best-effort; Indian market coverage is limited)
    // -------------------------------------------------------------------------

    FundamentalData fetchFromFinnhub(String symbol) {
        Long token = instrumentRegistry.getToken(symbol);
        String isin = instrumentRegistry.getIsin(symbol);
        String exchange = instrumentRegistry.getExchange(symbol);
        Integer lotSize = instrumentRegistry.getLotSize(symbol);

        // Finnhub uses a different symbol format for Indian stocks; company-profile2
        // and basic-financials endpoints are available but coverage is sparse.
        // We attempt to fetch; any non-null numeric values upgrade quality to PARTIAL.
        Double pe = null;
        Double eps = null;
        Double marketCap = null;
        Double high52w = null;
        Double low52w = null;
        Double dividendYield = null;
        Double bookValue = null;
        Double roe = null;
        Double debtToEquity = null;

        // FinnhubService currently only exposes news endpoints.
        // When fundamental endpoints are added (getBasicFinancials, getCompanyProfile),
        // they should be called here. For now we create an UNAVAILABLE entry so the
        // cache still tracks which symbols have been attempted.
        DataQuality quality = DataQuality.UNAVAILABLE;

        return new FundamentalData(
            symbol, token, isin, exchange,
            /* sector */ null, lotSize,
            pe, eps, marketCap,
            high52w, low52w,
            dividendYield, bookValue,
            roe, debtToEquity,
            LocalDate.now(), quality
        );
    }

    // -------------------------------------------------------------------------
    // Staleness check
    // -------------------------------------------------------------------------

    boolean isStale(FundamentalData data) {
        if (data.lastUpdated() == null) {
            return true;
        }
        return data.lastUpdated().plusDays(MAX_STALENESS_DAYS).isBefore(LocalDate.now());
    }

    // -------------------------------------------------------------------------
    // Disk persistence
    // -------------------------------------------------------------------------

    void loadFromDisk() {
        Path path = Paths.get(cacheFilePath);
        if (!Files.exists(path)) {
            log.info("No fundamental cache file found at {} — starting empty", cacheFilePath);
            return;
        }
        try {
            Map<String, FundamentalData> loaded = objectMapper.readValue(
                path.toFile(),
                new TypeReference<Map<String, FundamentalData>>() {}
            );
            cache.putAll(loaded);
            log.info("Loaded {} fundamental cache entries from disk", loaded.size());
        } catch (Exception e) {
            log.warn("Failed to load fundamental cache from {}: {}", cacheFilePath, e.getMessage());
        }
    }

    public void persistToDisk() {
        try {
            Path targetPath = Paths.get(cacheFilePath);
            Path dir = targetPath.getParent() != null ? targetPath.getParent() : Paths.get(".");
            Files.createDirectories(dir);

            // Atomic write: write to temp file, then rename
            File tempFile = File.createTempFile("fundamental-cache-", ".tmp", dir.toFile());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, cache);
            Files.move(tempFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Persisted {} fundamental cache entries to disk", cache.size());
        } catch (Exception e) {
            log.error("Failed to persist fundamental cache: {}", e.getMessage());
        }
    }
}
