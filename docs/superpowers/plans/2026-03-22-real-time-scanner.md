# Real-Time Market Scanner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-time market scanner to StockMan that streams Zerodha ticks, generates buy/sell signals from 10 technical indicators, and delivers alerts via Telegram, Discord, and a live WebSocket scanner page.

**Architecture:** In-process module with thread isolation. Split event architecture: bounded queues for tick data-plane, Spring events for signal/alert control-plane. Per-symbol serialized pipeline for thread safety. Single-user model.

**Tech Stack:** Spring Boot 3.2.2, Java 17, Zerodha KiteTicker SDK, java-telegram-bot-api, JDA 5.x, Spring WebSocket (STOMP/SockJS), Caffeine, Guava, Micrometer

**Spec:** `docs/superpowers/specs/2026-03-22-real-time-scanner-design.md`

---

## File Structure

### New Files

**Config:**
- `src/main/java/com/stockman/config/ScannerConfig.java` — `@ConfigurationProperties(prefix = "scanner")` for all scanner settings
- `src/main/java/com/stockman/config/AlertConfig.java` — `@ConfigurationProperties(prefix = "alerts")` for Telegram/Discord/WebSocket
- `src/main/java/com/stockman/config/WebSocketConfig.java` — STOMP/SockJS broker configuration + security

**Scanner core:**
- `src/main/java/com/stockman/scanner/model/TickSnapshot.java` — immutable tick record
- `src/main/java/com/stockman/scanner/model/Candle.java` — OHLCV candle record
- `src/main/java/com/stockman/scanner/model/FundamentalData.java` — fundamental data record with DataQuality
- `src/main/java/com/stockman/scanner/model/TradeSignal.java` — immutable signal record
- `src/main/java/com/stockman/scanner/model/AiEnrichment.java` — AI enrichment record
- `src/main/java/com/stockman/scanner/model/SignalEnums.java` — SignalType, SignalStrength, TradingStyle, DataQuality enums
- `src/main/java/com/stockman/scanner/model/IndicatorSnapshot.java` — snapshot of all indicator values
- `src/main/java/com/stockman/scanner/model/Watchlist.java` — watchlist model

**Data layer services:**
- `src/main/java/com/stockman/scanner/service/InstrumentRegistry.java` — symbol ↔ instrumentToken mapping
- `src/main/java/com/stockman/scanner/service/TickerService.java` — KiteTicker WebSocket wrapper + ingress pipeline
- `src/main/java/com/stockman/scanner/service/AuthStateManager.java` — session lifecycle state machine
- `src/main/java/com/stockman/scanner/service/FundamentalCacheService.java` — daily fundamental refresh
- `src/main/java/com/stockman/scanner/service/ExchangeCalendar.java` — market hours + holiday calendar

**Signal engine:**
- `src/main/java/com/stockman/scanner/engine/CandleBuilder.java` — tick → OHLCV candle aggregation
- `src/main/java/com/stockman/scanner/engine/IndicatorEngine.java` — EMA, RSI, MACD, etc.
- `src/main/java/com/stockman/scanner/engine/SignalDetector.java` — rule evaluation + signal generation
- `src/main/java/com/stockman/scanner/engine/SignalCooldownManager.java` — state machine + cooldown
- `src/main/java/com/stockman/scanner/engine/ScannerPipeline.java` — orchestrates per-symbol pipeline steps 1-7
- `src/main/java/com/stockman/scanner/engine/ScannerAiService.java` — AI enrichment (bypasses IntentClassifier)
- `src/main/java/com/stockman/scanner/engine/AiBudgeter.java` — priority queue + rate limiting for AI calls

**Alert service:**
- `src/main/java/com/stockman/scanner/alert/AlertOrchestrator.java` — event listener → channel routing
- `src/main/java/com/stockman/scanner/alert/AlertDeliveryState.java` — per-signalId per-channel state machine
- `src/main/java/com/stockman/scanner/alert/TelegramAlertChannel.java` — Telegram bot integration
- `src/main/java/com/stockman/scanner/alert/DiscordAlertChannel.java` — Discord bot integration
- `src/main/java/com/stockman/scanner/alert/WebSocketAlertChannel.java` — STOMP push
- `src/main/java/com/stockman/scanner/alert/SignalHistoryBuffer.java` — bounded ring buffer for catch-up

**Events:**
- `src/main/java/com/stockman/scanner/event/SignalEvent.java` — wraps TradeSignal + aiPending flag
- `src/main/java/com/stockman/scanner/event/AiEnrichmentEvent.java` — wraps AiEnrichment
- `src/main/java/com/stockman/scanner/event/SessionExpiryEvent.java`
- `src/main/java/com/stockman/scanner/event/ScanUniverseChangedEvent.java`

**Controllers:**
- `src/main/java/com/stockman/controller/ScannerController.java` — scanner REST endpoints
- `src/main/java/com/stockman/controller/UserPreferenceController.java` — watchlist + alert preferences
- `src/main/java/com/stockman/controller/AdminController.java` — admin alert config

**Lifecycle:**
- `src/main/java/com/stockman/scanner/ScannerLifecycleManager.java` — startup/shutdown orchestration

**Frontend:**
- `src/main/resources/static/scanner.html` — live scanner page
- `src/main/resources/static/scripts/scanner.js` — WebSocket client + signal rendering
- `src/main/resources/holidays.json` — NSE holiday calendar

**Tests:**
- `src/test/java/com/stockman/scanner/model/` — model tests
- `src/test/java/com/stockman/scanner/engine/` — CandleBuilder, IndicatorEngine, SignalDetector, Cooldown tests
- `src/test/java/com/stockman/scanner/service/` — InstrumentRegistry, ExchangeCalendar, FundamentalCache tests
- `src/test/java/com/stockman/scanner/alert/` — AlertOrchestrator, delivery state tests
- `src/test/java/com/stockman/controller/` — REST endpoint tests

### Modified Files
- `pom.xml` — add new dependencies
- `src/main/resources/application.yml` — add scanner + alerts config
- `src/main/java/com/stockman/service/ZerodhaService.java` — add `getActiveKiteConnect()` method
- `src/main/resources/static/styles/main.css` — scanner page styles
- `src/main/resources/static/scripts/api.js` — scanner API methods

---

## Phase 1: Foundation (Tasks 1-5)

### Task 1: Add Dependencies & Configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/stockman/config/ScannerConfig.java`
- Create: `src/main/java/com/stockman/config/AlertConfig.java`

- [ ] **Step 1: Add dependencies to pom.xml**

```xml
<!-- After existing dependencies -->

<!-- WebSocket -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- Telegram Bot -->
<dependency>
    <groupId>com.github.pengrad</groupId>
    <artifactId>java-telegram-bot-api</artifactId>
    <version>7.7.0</version>
</dependency>

<!-- Discord (JDA) -->
<dependency>
    <groupId>net.dv8tion</groupId>
    <artifactId>JDA</artifactId>
    <version>5.2.1</version>
    <exclusions>
        <exclusion>
            <groupId>club.minnced</groupId>
            <artifactId>opus-java</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- Caffeine cache -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>

<!-- Guava (RateLimiter) -->
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>33.0.0-jre</version>
</dependency>

<!-- Micrometer (metrics) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Also add JDA repository to `<repositories>`:
```xml
<repository>
    <id>dv8tion</id>
    <name>m2-dv8tion</name>
    <url>https://m2.dv8tion.net/releases</url>
</repository>
```

- [ ] **Step 2: Add scanner + alerts config to application.yml**

Append to existing `application.yml`:
```yaml
scanner:
  enabled: true
  connect-time: "09:00"
  disconnect-time: "15:35"
  silence-period-end: "09:16:30"
  warmup-minutes: 30
  holiday-calendar: classpath:holidays.json
  candle-window-size: 600
  tick-queue-capacity: 10000
  reconnect:
    initial-delay-seconds: 5
    max-delay-seconds: 60
    multiplier: 2.0
  cooldown:
    scalp-minutes: 5
    intraday-minutes: 15
    swing-minutes: 60
  ai-enrichment:
    enabled: true
    max-calls-per-minute: 10
    timeout-seconds: 15
    swing-gate-timeout-seconds: 15

alerts:
  telegram:
    enabled: false
    bot-token: ${TELEGRAM_BOT_TOKEN:}
    chat-id: ${TELEGRAM_CHAT_ID:}
  discord:
    enabled: false
    bot-token: ${DISCORD_BOT_TOKEN:}
    channel-ids:
      scalp: ${DISCORD_SCALP_CHANNEL:}
      intraday: ${DISCORD_INTRADAY_CHANNEL:}
      swing: ${DISCORD_SWING_CHANNEL:}
  websocket:
    enabled: true

admin:
  # Admin credential for /api/admin/* endpoints (via ADMIN_API_KEY env var)
  credential: ${ADMIN_API_KEY}
```

- [ ] **Step 3: Create ScannerConfig.java**

```java
package com.stockman.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "scanner")
@Validated
@Getter @Setter
public class ScannerConfig {
    private boolean enabled = true;
    private String connectTime = "09:00";
    private String disconnectTime = "15:35";
    private String silencePeriodEnd = "09:16:30";
    private int warmupMinutes = 30;
    private String holidayCalendar = "classpath:holidays.json";
    private int candleWindowSize = 600;
    private int tickQueueCapacity = 10000;

    private Reconnect reconnect = new Reconnect();
    private Cooldown cooldown = new Cooldown();
    private AiEnrichment aiEnrichment = new AiEnrichment();

    @Getter @Setter
    public static class Reconnect {
        private int initialDelaySeconds = 5;
        private int maxDelaySeconds = 60;
        private double multiplier = 2.0;
    }

    @Getter @Setter
    public static class Cooldown {
        private int scalpMinutes = 5;
        private int intradayMinutes = 15;
        private int swingMinutes = 60;
    }

    @Getter @Setter
    public static class AiEnrichment {
        private boolean enabled = true;
        private int maxCallsPerMinute = 10;
        private int timeoutSeconds = 15;
        private int swingGateTimeoutSeconds = 15;
    }
}
```

- [ ] **Step 4: Create AlertConfig.java**

```java
package com.stockman.config;

import jakarta.validation.constraints.NotBlank;
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
    private WebSocketConfig websocket = new WebSocketConfig();

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
        private Map<String, String> channelIds = Map.of();
    }

    @Getter @Setter
    public static class WebSocketConfig {
        private boolean enabled = true;
    }
}
```

- [ ] **Step 5: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/stockman/config/ScannerConfig.java src/main/java/com/stockman/config/AlertConfig.java
git commit -m "feat(scanner): add dependencies and configuration classes"
```

---

### Task 2: Data Models & Enums

**Files:**
- Create: `src/main/java/com/stockman/scanner/model/SignalEnums.java`
- Create: `src/main/java/com/stockman/scanner/model/TickSnapshot.java`
- Create: `src/main/java/com/stockman/scanner/model/Candle.java`
- Create: `src/main/java/com/stockman/scanner/model/FundamentalData.java`
- Create: `src/main/java/com/stockman/scanner/model/IndicatorSnapshot.java`
- Create: `src/main/java/com/stockman/scanner/model/TradeSignal.java`
- Create: `src/main/java/com/stockman/scanner/model/AiEnrichment.java`
- Create: `src/main/java/com/stockman/scanner/model/Watchlist.java`
- Test: `src/test/java/com/stockman/scanner/model/TradeSignalTest.java`

- [ ] **Step 1: Create SignalEnums.java**

```java
package com.stockman.scanner.model;

public class SignalEnums {
    public enum SignalType { BUY, SELL, STRONG_BUY, STRONG_SELL }
    public enum SignalStrength { WEAK, MODERATE, STRONG }
    public enum TradingStyle { SCALP, INTRADAY, SWING }
    public enum DataQuality { FRESH, STALE, PARTIAL, UNAVAILABLE }
    public enum AuthState { UNAUTHENTICATED, ACTIVE, DEGRADED }
    public enum DeliveryStatus { NEW, SENT, ENRICHED, FAILED, STALE }

    private SignalEnums() {}
}
```

- [ ] **Step 2: Create TickSnapshot.java**

```java
package com.stockman.scanner.model;

import java.time.Instant;

public record TickSnapshot(
    long instrumentToken,
    double ltp,
    double open, double high, double low,
    double previousClose,
    long volume,
    double bidPrice, double askPrice,
    Instant exchangeTimestamp,
    Instant receivedAt
) {}
```

- [ ] **Step 3: Create Candle.java**

```java
package com.stockman.scanner.model;

import java.time.Instant;

public record Candle(
    long instrumentToken,
    Instant openTime,
    double open, double high, double low, double close,
    long volume,
    double vwap
) {}
```

- [ ] **Step 4: Create FundamentalData.java**

```java
package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.DataQuality;
import java.time.LocalDate;

public record FundamentalData(
    String symbol,
    Long instrumentToken,
    String isin,
    String exchange,
    String sector,
    Integer lotSize,
    Double pe, Double eps, Double marketCap,
    Double high52w, Double low52w,
    Double dividendYield, Double bookValue,
    Double roe, Double debtToEquity,
    LocalDate lastUpdated,
    DataQuality quality
) {}
```

- [ ] **Step 5: Create IndicatorSnapshot.java**

```java
package com.stockman.scanner.model;

public record IndicatorSnapshot(
    double ema9, double ema21, double ema50, double ema200,
    double rsi,
    double macdLine, double macdSignal, double macdHistogram,
    double stochasticK, double stochasticD,
    double vwap,
    double superTrend, boolean superTrendBullish,
    double obv,
    double rvol,
    double atr,
    boolean warmedUp
) {}
```

- [ ] **Step 6: Create TradeSignal.java**

```java
package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.*;
import java.time.Instant;
import java.util.List;

public record TradeSignal(
    String signalId,
    String symbol, Long instrumentToken,
    SignalType type, SignalStrength strength,
    TradingStyle style,
    double entryPrice, double stopLoss, double target,
    List<String> triggerReasons,
    IndicatorSnapshot indicators,
    Instant generatedAt
) {
    public static String buildSignalId(String symbol, TradingStyle style,
            String timeframe, Instant candleClose, SignalType type) {
        return symbol + ":" + style + ":" + timeframe + ":" +
               candleClose.toEpochMilli() + ":" + type;
    }
}
```

- [ ] **Step 7: Create AiEnrichment.java**

```java
package com.stockman.scanner.model;

import java.time.Instant;

public record AiEnrichment(
    String signalId,
    String insight,
    double aiConfidence,
    String[] agentsUsed,
    Instant completedAt
) {}
```

- [ ] **Step 8: Create Watchlist.java**

```java
package com.stockman.scanner.model;

import java.time.Instant;
import java.util.Set;

public record Watchlist(
    Set<String> symbols,
    Instant lastModified
) {}
```

- [ ] **Step 9: Write test for TradeSignal.buildSignalId**

```java
package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TradeSignalTest {
    @Test
    void buildSignalId_produceDeterministicId() {
        Instant candleClose = Instant.parse("2026-03-22T04:15:00Z");
        String id1 = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        String id2 = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        assertEquals(id1, id2);
        assertTrue(id1.contains("RELIANCE"));
        assertTrue(id1.contains("INTRADAY"));
    }

    @Test
    void buildSignalId_differentInputsProduceDifferentIds() {
        Instant candleClose = Instant.parse("2026-03-22T04:15:00Z");
        String buy = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.BUY);
        String sell = TradeSignal.buildSignalId("RELIANCE", TradingStyle.INTRADAY,
                "5m", candleClose, SignalType.SELL);
        assertNotEquals(buy, sell);
    }
}
```

- [ ] **Step 10: Run test**

Run: `./mvnw test -Dtest=TradeSignalTest -pl .`
Expected: 2 tests PASS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/stockman/scanner/model/ src/test/java/com/stockman/scanner/model/
git commit -m "feat(scanner): add data models and enums"
```

---

### Task 3: Exchange Calendar & Holiday Support

**Files:**
- Create: `src/main/java/com/stockman/scanner/service/ExchangeCalendar.java`
- Create: `src/main/resources/holidays.json`
- Test: `src/test/java/com/stockman/scanner/service/ExchangeCalendarTest.java`

- [ ] **Step 1: Create holidays.json**

```json
{
  "exchange": "NSE",
  "year": 2026,
  "holidays": [
    "2026-01-26", "2026-03-10", "2026-03-17", "2026-03-30",
    "2026-03-31", "2026-04-06", "2026-04-14", "2026-04-21",
    "2026-05-01", "2026-07-07", "2026-08-15", "2026-08-19",
    "2026-10-02", "2026-10-15", "2026-10-21", "2026-10-22",
    "2026-11-04", "2026-11-18", "2026-12-25"
  ],
  "specialSessions": [
    { "date": "2026-11-18", "name": "Muhurat Trading", "startTime": "18:15", "endTime": "19:45" }
  ]
}
```

- [ ] **Step 2: Write failing tests for ExchangeCalendar**

```java
package com.stockman.scanner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarTest {
    private ExchangeCalendar calendar;

    @BeforeEach
    void setUp() {
        calendar = new ExchangeCalendar("classpath:holidays.json");
    }

    @Test
    void isTradingDay_weekday_notHoliday_returnsTrue() {
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 3, 23))); // Monday
    }

    @Test
    void isTradingDay_weekend_returnsFalse() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 3, 22))); // Sunday
    }

    @Test
    void isTradingDay_holiday_returnsFalse() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 1, 26))); // Republic Day
    }

    @Test
    void isWithinMarketHours_duringSession_returnsTrue() {
        LocalTime time = LocalTime.of(10, 30);
        assertTrue(calendar.isWithinMarketHours(time));
    }

    @Test
    void isWithinMarketHours_beforeOpen_returnsFalse() {
        assertFalse(calendar.isWithinMarketHours(LocalTime.of(8, 0)));
    }

    @Test
    void isWithinMarketHours_afterClose_returnsFalse() {
        assertFalse(calendar.isWithinMarketHours(LocalTime.of(16, 0)));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest=ExchangeCalendarTest`
Expected: FAIL — class does not exist

- [ ] **Step 4: Implement ExchangeCalendar**

```java
package com.stockman.scanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.*;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class ExchangeCalendar {
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Set<LocalDate> holidays = new HashSet<>();

    public ExchangeCalendar(String holidayCalendarPath) {
        loadHolidays(holidayCalendarPath);
    }

    private void loadHolidays(String path) {
        try {
            InputStream is = new DefaultResourceLoader().getResource(path).getInputStream();
            JsonNode root = new ObjectMapper().readTree(is);
            root.get("holidays").forEach(node ->
                holidays.add(LocalDate.parse(node.asText()))
            );
            log.info("Loaded {} holidays from {}", holidays.size(), path);
        } catch (Exception e) {
            log.warn("Failed to load holiday calendar from {}: {}", path, e.getMessage());
        }
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !holidays.contains(date);
    }

    public boolean isWithinMarketHours(LocalTime time) {
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    public LocalTime getMarketOpen() { return MARKET_OPEN; }
    public LocalTime getMarketClose() { return MARKET_CLOSE; }
    public ZoneId getTimeZone() { return IST; }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ExchangeCalendarTest`
Expected: 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockman/scanner/service/ExchangeCalendar.java src/main/resources/holidays.json src/test/java/com/stockman/scanner/service/ExchangeCalendarTest.java
git commit -m "feat(scanner): add ExchangeCalendar with holiday support"
```

---

### Task 4: Instrument Registry

**Files:**
- Create: `src/main/java/com/stockman/scanner/service/InstrumentRegistry.java`
- Test: `src/test/java/com/stockman/scanner/service/InstrumentRegistryTest.java`

- [ ] **Step 1: Write failing tests**

```java
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=InstrumentRegistryTest`
Expected: FAIL

- [ ] **Step 3: Implement InstrumentRegistry**

```java
package com.stockman.scanner.service;

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

    public void register(String symbol, long token, String isin, String exchange, int lotSize) {
        symbolToToken.put(symbol, token);
        tokenToSymbol.put(token, symbol);
        symbolToIsin.put(symbol, isin);
        symbolToExchange.put(symbol, exchange);
        symbolToLotSize.put(symbol, lotSize);
    }

    public Long getToken(String symbol) { return symbolToToken.get(symbol); }
    public String getSymbol(long token) { return tokenToSymbol.get(token); }
    public String getIsin(String symbol) { return symbolToIsin.get(symbol); }
    public String getExchange(String symbol) { return symbolToExchange.get(symbol); }
    public Integer getLotSize(String symbol) { return symbolToLotSize.getOrDefault(symbol, 1); }
    public Set<Long> getAllTokens() { return Set.copyOf(tokenToSymbol.keySet()); }
    public int size() { return symbolToToken.size(); }

    public void clear() {
        symbolToToken.clear();
        tokenToSymbol.clear();
        symbolToIsin.clear();
        symbolToExchange.clear();
        symbolToLotSize.clear();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=InstrumentRegistryTest`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockman/scanner/service/InstrumentRegistry.java src/test/java/com/stockman/scanner/service/InstrumentRegistryTest.java
git commit -m "feat(scanner): add InstrumentRegistry for symbol-token mapping"
```

---

### Task 5: Event Classes & ZerodhaService Extension

**Files:**
- Create: `src/main/java/com/stockman/scanner/event/SignalEvent.java`
- Create: `src/main/java/com/stockman/scanner/event/AiEnrichmentEvent.java`
- Create: `src/main/java/com/stockman/scanner/event/SessionExpiryEvent.java`
- Create: `src/main/java/com/stockman/scanner/event/ScanUniverseChangedEvent.java`
- Modify: `src/main/java/com/stockman/service/ZerodhaService.java`

- [ ] **Step 1: Create event classes**

```java
// SignalEvent.java
package com.stockman.scanner.event;

import com.stockman.scanner.model.TradeSignal;
import org.springframework.context.ApplicationEvent;

public class SignalEvent extends ApplicationEvent {
    private final TradeSignal signal;
    private final boolean aiPending;

    public SignalEvent(Object source, TradeSignal signal, boolean aiPending) {
        super(source);
        this.signal = signal;
        this.aiPending = aiPending;
    }

    public TradeSignal getSignal() { return signal; }
    public boolean isAiPending() { return aiPending; }
}
```

```java
// AiEnrichmentEvent.java
package com.stockman.scanner.event;

import com.stockman.scanner.model.AiEnrichment;
import org.springframework.context.ApplicationEvent;

public class AiEnrichmentEvent extends ApplicationEvent {
    private final AiEnrichment enrichment;

    public AiEnrichmentEvent(Object source, AiEnrichment enrichment) {
        super(source);
        this.enrichment = enrichment;
    }

    public AiEnrichment getEnrichment() { return enrichment; }
}
```

```java
// SessionExpiryEvent.java
package com.stockman.scanner.event;

import org.springframework.context.ApplicationEvent;

public class SessionExpiryEvent extends ApplicationEvent {
    private final String reason;

    public SessionExpiryEvent(Object source, String reason) {
        super(source);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
```

```java
// ScanUniverseChangedEvent.java
package com.stockman.scanner.event;

import org.springframework.context.ApplicationEvent;
import java.util.Set;

public class ScanUniverseChangedEvent extends ApplicationEvent {
    private final Set<Long> instrumentTokens;

    public ScanUniverseChangedEvent(Object source, Set<Long> instrumentTokens) {
        super(source);
        this.instrumentTokens = instrumentTokens;
    }

    public Set<Long> getInstrumentTokens() { return instrumentTokens; }
}
```

- [ ] **Step 2: Add getActiveKiteConnect() to ZerodhaService**

Read the current `ZerodhaService.java` to find the session map, then add:

```java
// Add to ZerodhaService.java - new method after existing session management methods
public KiteConnect getActiveKiteConnect() {
    // Return the most recently authenticated session
    return kiteConnectMap.values().stream()
        .findFirst()
        .orElse(null);
}
```

Note: Read the actual field name in ZerodhaService (likely `kiteConnectMap` or similar `ConcurrentHashMap<String, KiteConnect>`) and use the correct field name.

- [ ] **Step 3: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/event/ src/main/java/com/stockman/service/ZerodhaService.java
git commit -m "feat(scanner): add event classes and ZerodhaService.getActiveKiteConnect()"
```

---

## Phase 2: Signal Engine (Tasks 6-10)

### Task 6: Candle Builder

**Files:**
- Create: `src/main/java/com/stockman/scanner/engine/CandleBuilder.java`
- Test: `src/test/java/com/stockman/scanner/engine/CandleBuilderTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.TickSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CandleBuilderTest {
    private CandleBuilder builder;
    private static final long TOKEN = 738561L;

    @BeforeEach
    void setUp() {
        builder = new CandleBuilder(600); // window size
    }

    private TickSnapshot tick(double ltp, long volume, String time) {
        return new TickSnapshot(TOKEN, ltp, 100, 105, 95, 99,
                volume, ltp - 0.1, ltp + 0.1,
                Instant.parse(time), Instant.now());
    }

    @Test
    void onTick_firstTick_createsOpenCandle() {
        builder.onTick(tick(100.0, 1000, "2026-03-23T03:46:00Z"));
        var candles = builder.getCompletedCandles(TOKEN, "1m");
        assertTrue(candles.isEmpty()); // no completed candle yet
    }

    @Test
    void onTick_tickInNextMinute_closesCandle() {
        builder.onTick(tick(100.0, 1000, "2026-03-23T03:46:00Z")); // 9:16 IST
        builder.onTick(tick(102.0, 500, "2026-03-23T03:46:30Z"));  // still same minute
        builder.onTick(tick(103.0, 800, "2026-03-23T03:47:00Z"));  // next minute → closes

        var candles = builder.getCompletedCandles(TOKEN, "1m");
        assertEquals(1, candles.size());
        Candle c = candles.get(0);
        assertEquals(100.0, c.open());
        assertEquals(102.0, c.high());
        assertEquals(100.0, c.low());
        assertEquals(102.0, c.close()); // last tick in that minute
    }

    @Test
    void onTick_ohlcCalculatedCorrectly() {
        builder.onTick(tick(100.0, 1000, "2026-03-23T03:46:00Z"));
        builder.onTick(tick(105.0, 500, "2026-03-23T03:46:10Z"));
        builder.onTick(tick(95.0, 200, "2026-03-23T03:46:20Z"));
        builder.onTick(tick(102.0, 300, "2026-03-23T03:46:50Z"));
        builder.onTick(tick(101.0, 100, "2026-03-23T03:47:01Z")); // closes

        var candles = builder.getCompletedCandles(TOKEN, "1m");
        assertEquals(1, candles.size());
        Candle c = candles.get(0);
        assertEquals(100.0, c.open());
        assertEquals(105.0, c.high());
        assertEquals(95.0, c.low());
        assertEquals(102.0, c.close());
        assertEquals(2000, c.volume()); // 1000+500+200+300
    }

    @Test
    void emptyMinute_carryForwardCandle() {
        builder.onTick(tick(100.0, 1000, "2026-03-23T03:46:00Z"));
        builder.onTick(tick(102.0, 500, "2026-03-23T03:46:30Z"));
        // Skip minute 47 entirely
        builder.onTick(tick(105.0, 800, "2026-03-23T03:48:00Z")); // triggers close of 46 AND carry-forward for 47

        var candles = builder.getCompletedCandles(TOKEN, "1m");
        assertTrue(candles.size() >= 2); // minute 46 + carry-forward for 47
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CandleBuilderTest`
Expected: FAIL

- [ ] **Step 3: Implement CandleBuilder**

The implementation should:
- Maintain per-instrument `CandleAccumulator` (current open candle state: open, high, low, close, volume, vwap numerator/denominator)
- On tick: determine minute boundary from `exchangeTimestamp`. If new minute → finalize previous candle, add to completed list, start new accumulator
- Handle empty minutes by emitting carry-forward candles
- 5m/15m candles: derived by aggregating completed 1m candles at appropriate boundaries
- Sliding window: keep last `windowSize` completed candles per timeframe per instrument

Implementation is ~150 lines. Create with the standard OHLCV accumulation logic.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CandleBuilderTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockman/scanner/engine/CandleBuilder.java src/test/java/com/stockman/scanner/engine/CandleBuilderTest.java
git commit -m "feat(scanner): add CandleBuilder with event-time closing"
```

---

### Task 7: Indicator Engine

**Files:**
- Create: `src/main/java/com/stockman/scanner/engine/IndicatorEngine.java`
- Test: `src/test/java/com/stockman/scanner/engine/IndicatorEngineTest.java`

- [ ] **Step 1: Write failing tests for EMA, RSI, MACD**

```java
package com.stockman.scanner.engine;

import com.stockman.scanner.model.Candle;
import com.stockman.scanner.model.IndicatorSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IndicatorEngineTest {
    private IndicatorEngine engine;
    private static final long TOKEN = 738561L;

    @BeforeEach
    void setUp() {
        engine = new IndicatorEngine();
    }

    private Candle candle(double close, long vol, int minuteOffset) {
        return new Candle(TOKEN,
            Instant.parse("2026-03-23T03:45:00Z").plusSeconds(minuteOffset * 60L),
            close - 1, close + 1, close - 2, close, vol, close);
    }

    private void feedCandles(int count, double basePrice) {
        for (int i = 0; i < count; i++) {
            engine.onCandleClose(TOKEN, candle(basePrice + (i % 10) * 0.5, 1000, i));
        }
    }

    @Test
    void afterWarmup_indicatorsAreAvailable() {
        feedCandles(210, 100.0); // > 200 candles for EMA200
        IndicatorSnapshot snap = engine.getSnapshot(TOKEN);
        assertNotNull(snap);
        assertTrue(snap.warmedUp());
        assertTrue(Double.isFinite(snap.ema9()));
        assertTrue(Double.isFinite(snap.rsi()));
        assertTrue(Double.isFinite(snap.macdLine()));
    }

    @Test
    void beforeWarmup_notWarmedUp() {
        feedCandles(10, 100.0);
        IndicatorSnapshot snap = engine.getSnapshot(TOKEN);
        assertNotNull(snap);
        assertFalse(snap.warmedUp());
    }

    @Test
    void ema9_reactsToRecentPrices() {
        feedCandles(50, 100.0);
        double emaBefore = engine.getSnapshot(TOKEN).ema9();
        // Feed higher prices
        for (int i = 50; i < 60; i++) {
            engine.onCandleClose(TOKEN, candle(120.0, 1000, i));
        }
        double emaAfter = engine.getSnapshot(TOKEN).ema9();
        assertTrue(emaAfter > emaBefore);
    }

    @Test
    void rsi_overSoldAfterDrops() {
        feedCandles(30, 100.0);
        // Feed 14 consecutive drops
        for (int i = 30; i < 44; i++) {
            engine.onCandleClose(TOKEN, candle(100.0 - (i - 30) * 2, 1000, i));
        }
        double rsi = engine.getSnapshot(TOKEN).rsi();
        assertTrue(rsi < 40, "RSI should be low after drops, was: " + rsi);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=IndicatorEngineTest`
Expected: FAIL

- [ ] **Step 3: Implement IndicatorEngine**

The implementation should have:
- Per-instrument `IndicatorState` object holding all running values
- `onCandleClose(long instrumentToken, Candle candle)` — updates all indicators incrementally
- EMA: Wilder smoothing `ema = (close - prevEma) * multiplier + prevEma`
- RSI: Wilder smoothing on avg gain/loss
- MACD: EMA12 - EMA26, signal = EMA9 of MACD line
- Stochastic: monotonic deques for rolling highest-high / lowest-low
- SuperTrend: ATR via Wilder, band flip logic
- OBV: cumulative volume (add on up candle, subtract on down)
- RVOL: current volume / 20-period SMA of volume
- `getSnapshot(long instrumentToken)` → `IndicatorSnapshot` record
- `getPreviousSnapshot(long instrumentToken)` → for crossover detection
- Warm-up tracking: `warmedUp = candleCount >= 200`

Implementation is ~250-300 lines. All indicator math is incremental O(1).

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=IndicatorEngineTest`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockman/scanner/engine/IndicatorEngine.java src/test/java/com/stockman/scanner/engine/IndicatorEngineTest.java
git commit -m "feat(scanner): add IndicatorEngine with 10 technical indicators"
```

---

### Task 8: Signal Detector & Cooldown Manager

**Files:**
- Create: `src/main/java/com/stockman/scanner/engine/SignalDetector.java`
- Create: `src/main/java/com/stockman/scanner/engine/SignalCooldownManager.java`
- Test: `src/test/java/com/stockman/scanner/engine/SignalDetectorTest.java`
- Test: `src/test/java/com/stockman/scanner/engine/SignalCooldownManagerTest.java`

- [ ] **Step 1: Write failing tests for SignalCooldownManager**

```java
package com.stockman.scanner.engine;

import com.stockman.scanner.model.SignalEnums.*;
import com.stockman.scanner.config.ScannerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignalCooldownManagerTest {
    private SignalCooldownManager manager;

    @BeforeEach
    void setUp() {
        var config = new ScannerConfig();
        manager = new SignalCooldownManager(config);
    }

    @Test
    void firstSignal_allowed() {
        assertTrue(manager.canEmitSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE));
    }

    @Test
    void duplicateSignal_blocked() {
        manager.recordSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertFalse(manager.canEmitSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE));
    }

    @Test
    void oppositeSignal_moderateStrength_allowed() {
        manager.recordSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertTrue(manager.canEmitSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.MODERATE));
    }

    @Test
    void oppositeSignal_weakStrength_blocked() {
        manager.recordSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.BUY, SignalStrength.MODERATE);
        assertFalse(manager.canEmitSignal("RELIANCE", TradingStyle.INTRADAY, "5m", SignalType.SELL, SignalStrength.WEAK));
    }

    @Test
    void differentTimeframe_notBlocked() {
        manager.recordSignal("RELIANCE", TradingStyle.SCALP, "1m", SignalType.BUY, SignalStrength.MODERATE);
        assertTrue(manager.canEmitSignal("RELIANCE", TradingStyle.SWING, "15m", SignalType.BUY, SignalStrength.MODERATE));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=SignalCooldownManagerTest`
Expected: FAIL

- [ ] **Step 3: Implement SignalCooldownManager**

Key points: keyed by `symbol+style+timeframe`, CAS state transitions via `AtomicReference`, cooldown periods from config, opposite signal bypass for strength >= MODERATE.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=SignalCooldownManagerTest`
Expected: 5 tests PASS

- [ ] **Step 5: Write failing tests for SignalDetector**

Test that given specific indicator snapshots, the correct signal type/strength is produced. Test multi-confirmation (2+ indicators → STRONG). Test that no signal is emitted when indicators don't trigger.

- [ ] **Step 6: Implement SignalDetector**

Key points: takes `IndicatorSnapshot` (current + previous), `Candle`, `FundamentalData`, and evaluates all buy/sell rules. Returns `Optional<TradeSignal>` or empty. ATR-based stop-loss/target calculation.

- [ ] **Step 7: Run all signal engine tests**

Run: `./mvnw test -Dtest="SignalDetectorTest,SignalCooldownManagerTest"`
Expected: All PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/stockman/scanner/engine/SignalDetector.java src/main/java/com/stockman/scanner/engine/SignalCooldownManager.java src/test/java/com/stockman/scanner/engine/
git commit -m "feat(scanner): add SignalDetector and SignalCooldownManager"
```

---

### Task 9: Scanner Pipeline Orchestrator

**Files:**
- Create: `src/main/java/com/stockman/scanner/engine/ScannerPipeline.java`
- Test: `src/test/java/com/stockman/scanner/engine/ScannerPipelineTest.java`

- [ ] **Step 1: Write failing integration test**

Test that feeding ticks through the pipeline produces expected signals. Mock the ApplicationEventPublisher to capture emitted SignalEvents.

- [ ] **Step 2: Implement ScannerPipeline**

Orchestrates the 7-step pipeline per-symbol:
1. `candleBuilder.onTick(tick)` → check if candle finalized
2. If candle finalized → derive 5m/15m if at boundary
3. `indicatorEngine.onCandleClose(token, candle)` for each finalized timeframe
4. For each timeframe: `signalDetector.evaluate(...)` → Optional<TradeSignal>
5. If signal present: `cooldownManager.canEmitSignal(...)` → check
6. If allowed: `eventPublisher.publishEvent(new SignalEvent(...))`
7. If AI enabled: submit to AI enrichment queue

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=ScannerPipelineTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/engine/ScannerPipeline.java src/test/java/com/stockman/scanner/engine/ScannerPipelineTest.java
git commit -m "feat(scanner): add ScannerPipeline orchestrating 7-step per-symbol flow"
```

---

### Task 10: AI Enrichment Service

**Files:**
- Create: `src/main/java/com/stockman/scanner/engine/ScannerAiService.java`
- Create: `src/main/java/com/stockman/scanner/engine/AiBudgeter.java`
- Test: `src/test/java/com/stockman/scanner/engine/AiBudgeterTest.java`

- [ ] **Step 1: Write failing tests for AiBudgeter**

Test priority queue ordering (STRONG > MODERATE > WEAK, SWING > INTRADAY > SCALP). Test rate limiting (max calls/minute). Test stale signal rejection.

- [ ] **Step 2: Implement AiBudgeter**

Priority queue with rate limiting via Guava `RateLimiter`. Checks signal validity before making AI call.

- [ ] **Step 3: Implement ScannerAiService**

Bypasses `IntentClassifier`. Directly invokes TECHNICAL + FUNDAMENTAL agents via `OpenRouterModelService` with a purpose-built prompt template containing signal details + indicator snapshot + fundamental data. Publishes `AiEnrichmentEvent` when done.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=AiBudgeterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockman/scanner/engine/ScannerAiService.java src/main/java/com/stockman/scanner/engine/AiBudgeter.java src/test/java/com/stockman/scanner/engine/AiBudgeterTest.java
git commit -m "feat(scanner): add ScannerAiService and AiBudgeter"
```

---

## Phase 3: Data Layer Services (Tasks 11-13)

### Task 11: TickerService (WebSocket Client)

**Files:**
- Create: `src/main/java/com/stockman/scanner/service/TickerService.java`
- Create: `src/main/java/com/stockman/scanner/service/AuthStateManager.java`

- [ ] **Step 1: Implement AuthStateManager**

State machine with `AtomicReference<AuthState>`. Methods: `validateSession()`, `transitionTo(state)`, `isActive()`. Publishes `SessionExpiryEvent` on transition to DEGRADED.

- [ ] **Step 2: Implement TickerService**

Two-tier queue architecture:
- Creates `KiteTicker` from `ZerodhaService.getActiveKiteConnect()`
- `onTick` callback → creates `TickSnapshot` → offers to main `ArrayBlockingQueue(10,000)`
- Single `ticker-ingress` thread dequeues and routes to 4 per-worker `ArrayBlockingQueue(2,500)` by `instrumentToken % 4`
- 4 `scanner-worker` threads each drain their queue, calling `ScannerPipeline.processTick(tick)`
- Market hours awareness via `ExchangeCalendar`
- Reconnect with exponential backoff, market hours check
- `AtomicReference<Set<Long>>` for subscribed instruments, re-subscribe on reconnect

- [ ] **Step 3: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/service/TickerService.java src/main/java/com/stockman/scanner/service/AuthStateManager.java
git commit -m "feat(scanner): add TickerService with two-tier queue and AuthStateManager"
```

---

### Task 12: Fundamental Cache Service

**Files:**
- Create: `src/main/java/com/stockman/scanner/service/FundamentalCacheService.java`
- Test: `src/test/java/com/stockman/scanner/service/FundamentalCacheServiceTest.java`

- [ ] **Step 1: Write failing tests**

Test cache get/put, staleness check (>7 days), JSON persistence (write/read), DataQuality enum handling.

- [ ] **Step 2: Implement FundamentalCacheService**

`@Scheduled` daily refresh at 7:00 AM IST. Guava RateLimiter for Finnhub. Atomic JSON file write. Falls back to Zerodha instrument CSV for 52-week data.

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=FundamentalCacheServiceTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/service/FundamentalCacheService.java src/test/java/com/stockman/scanner/service/FundamentalCacheServiceTest.java
git commit -m "feat(scanner): add FundamentalCacheService with daily refresh"
```

---

### Task 13: Scanner Lifecycle Manager

**Files:**
- Create: `src/main/java/com/stockman/scanner/ScannerLifecycleManager.java`

- [ ] **Step 1: Implement ScannerLifecycleManager**

`@Component` with `@PostConstruct` for startup and `@PreDestroy` for shutdown.

Startup: validate config → init InstrumentRegistry → start FundamentalCacheService → start TickerService (if market hours + trading day).

Shutdown: ordered teardown per spec section 9C (ticker → drain queues → workers → AI → alerts → bots → STOMP → persist).

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stockman/scanner/ScannerLifecycleManager.java
git commit -m "feat(scanner): add ScannerLifecycleManager for startup/shutdown"
```

---

## Phase 4: Alert Service (Tasks 14-18)

### Task 14: Alert Delivery State & Signal History Buffer

**Files:**
- Create: `src/main/java/com/stockman/scanner/alert/AlertDeliveryState.java`
- Create: `src/main/java/com/stockman/scanner/alert/SignalHistoryBuffer.java`
- Test: `src/test/java/com/stockman/scanner/alert/SignalHistoryBufferTest.java`
- Test: `src/test/java/com/stockman/scanner/alert/AlertDeliveryStateTest.java`

- [ ] **Step 1: Write failing tests for SignalHistoryBuffer**

Test: add signals, bounded eviction at 500, cursor-based retrieval, enrichment co-storage, day-end eviction.

- [ ] **Step 2: Implement SignalHistoryBuffer**

Bounded `ConcurrentLinkedDeque` with size tracking. Cursor-based `getSignalsSince(signalId, limit)`. Attach `AiEnrichment` by signalId.

- [ ] **Step 3: Write failing tests for AlertDeliveryState**

Test CAS transitions: NEW → SENT → ENRICHED. Test enrichment-before-send buffering. Test concurrent transition safety.

- [ ] **Step 4: Implement AlertDeliveryState**

Per signalId × channel tracking with `AtomicReference<DeliveryStatus>`, messageRef storage, Caffeine pending buffer (20s TTL).

- [ ] **Step 5: Run tests**

Run: `./mvnw test -Dtest="SignalHistoryBufferTest,AlertDeliveryStateTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockman/scanner/alert/AlertDeliveryState.java src/main/java/com/stockman/scanner/alert/SignalHistoryBuffer.java src/test/java/com/stockman/scanner/alert/
git commit -m "feat(scanner): add AlertDeliveryState and SignalHistoryBuffer"
```

---

### Task 15: Telegram Alert Channel

**Files:**
- Create: `src/main/java/com/stockman/scanner/alert/TelegramAlertChannel.java`

- [ ] **Step 1: Implement TelegramAlertChannel**

Uses `com.pengrad.telegrambot.TelegramBot`. Methods: `sendSignal(TradeSignal)` → returns message_id. `editWithEnrichment(messageId, AiEnrichment)` → edits message. HTML formatting for signal details. Handles 429 with `retry_after`. Error tiering (auth/channel/message level).

Commands: `/start`, `/stop`, `/status`, `/style`, `/strength` via `setUpdatesListener`.

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stockman/scanner/alert/TelegramAlertChannel.java
git commit -m "feat(scanner): add TelegramAlertChannel with bot commands"
```

---

### Task 16: Discord Alert Channel

**Files:**
- Create: `src/main/java/com/stockman/scanner/alert/DiscordAlertChannel.java`

- [ ] **Step 1: Implement DiscordAlertChannel**

Uses JDA. Color-coded embeds (green BUY, red SELL). Separate channels per trading style. Edit embeds for AI enrichment. Let JDA handle rate limiting natively. Slash commands: `/status`, `/style`, `/strength`.

- [ ] **Step 2: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/stockman/scanner/alert/DiscordAlertChannel.java
git commit -m "feat(scanner): add DiscordAlertChannel with embeds"
```

---

### Task 17: WebSocket Alert Channel & Config

**Files:**
- Create: `src/main/java/com/stockman/scanner/alert/WebSocketAlertChannel.java`
- Create: `src/main/java/com/stockman/config/WebSocketConfig.java`

- [ ] **Step 1: Implement WebSocketConfig**

```java
package com.stockman.config;

import com.stockman.config.AlertConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final AlertConfig alertConfig;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        if (alertConfig.getWebsocket().isEnabled()) {
            registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*")
                .withSockJS();
        }
    }
}
```

Add `ChannelInterceptor` to deny client SEND, validate SUBSCRIBE destinations, validate session on CONNECT.

- [ ] **Step 2: Implement WebSocketAlertChannel**

Uses `SimpMessagingTemplate` to push to `/topic/signals`, `/topic/ai-enrichment`, `/topic/scanner-status`.

- [ ] **Step 3: Verify build compiles**

Run: `./mvnw clean compile -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/alert/WebSocketAlertChannel.java src/main/java/com/stockman/config/WebSocketConfig.java
git commit -m "feat(scanner): add WebSocket alert channel with STOMP/SockJS"
```

---

### Task 18: Alert Orchestrator

**Files:**
- Create: `src/main/java/com/stockman/scanner/alert/AlertOrchestrator.java`
- Test: `src/test/java/com/stockman/scanner/alert/AlertOrchestratorTest.java`

- [ ] **Step 1: Write failing tests**

Test: signal dispatched to all enabled channels. Test stale signal dropping. Test AI enrichment routed as edit. Test channel failure isolation.

- [ ] **Step 2: Implement AlertOrchestrator**

`@EventListener @Async("alertExecutor")` for `SignalEvent` and `AiEnrichmentEvent`. Bounded `ThreadPoolTaskExecutor(core=8, max=16, queue=500)` with `DiscardWithMetricPolicy`. Stale alert dropping by signal age. Routes to Telegram/Discord/WebSocket channels. Manages `AlertDeliveryState` per signalId × channel.

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=AlertOrchestratorTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/stockman/scanner/alert/AlertOrchestrator.java src/test/java/com/stockman/scanner/alert/AlertOrchestratorTest.java
git commit -m "feat(scanner): add AlertOrchestrator with tiered error handling"
```

---

## Phase 5: REST API & Frontend (Tasks 19-21)

### Task 19: REST Controllers

**Files:**
- Create: `src/main/java/com/stockman/controller/ScannerController.java`
- Create: `src/main/java/com/stockman/controller/UserPreferenceController.java`
- Create: `src/main/java/com/stockman/controller/AdminController.java`
- Test: `src/test/java/com/stockman/controller/ScannerControllerTest.java`

- [ ] **Step 1: Implement ScannerController**

Endpoints: `GET /api/scanner/status`, `GET /api/scanner/signals` (cursor pagination), `GET /api/scanner/signals/{signalId}`, `GET /api/scanner/indicators/{symbol}`.

- [ ] **Step 2: Implement UserPreferenceController**

Endpoints: `GET/PUT /api/user/watchlist`, `GET/PUT /api/user/alerts/preferences`. PUT watchlist publishes `ScanUniverseChangedEvent`.

- [ ] **Step 3: Implement AdminController**

Endpoint: `GET/PUT /api/admin/alerts/config`. Protected by `X-Admin-Key` header check. Secrets masked in GET response. Audit logged.

- [ ] **Step 4: Write controller tests**

Test status endpoint returns expected JSON. Test cursor pagination. Test admin auth rejection without key.

- [ ] **Step 5: Run tests**

Run: `./mvnw test -Dtest=ScannerControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/stockman/controller/ScannerController.java src/main/java/com/stockman/controller/UserPreferenceController.java src/main/java/com/stockman/controller/AdminController.java src/test/java/com/stockman/controller/ScannerControllerTest.java
git commit -m "feat(scanner): add REST controllers for scanner, user prefs, admin"
```

---

### Task 20: Scanner Frontend Page

**Files:**
- Create: `src/main/resources/static/scanner.html`
- Create: `src/main/resources/static/scripts/scanner.js`
- Modify: `src/main/resources/static/scripts/api.js`
- Modify: `src/main/resources/static/styles/main.css`

- [ ] **Step 1: Add scanner API methods to api.js**

```javascript
async getScannerStatus() { return this.get('/api/scanner/status'); },
async getScannerSignals(cursor, limit = 50) {
    const params = new URLSearchParams({ limit });
    if (cursor) params.set('cursor', cursor);
    return this.get(`/api/scanner/signals?${params}`);
},
async getScannerIndicators(symbol) { return this.get(`/api/scanner/indicators/${symbol}`); },
async getWatchlist() { return this.get('/api/user/watchlist'); },
async updateWatchlist(symbols) { return this.put('/api/user/watchlist', { symbols }); },
```

- [ ] **Step 2: Create scanner.html**

Page structure: status bar (top), filter controls, signal feed (scrollable, newest first), signal cards with expandable indicator panel. Navigation link from other pages.

- [ ] **Step 3: Create scanner.js**

STOMP/SockJS client connecting to `/ws`. Subscribes to `/topic/signals`, `/topic/ai-enrichment`, `/topic/scanner-status`. On `SignalEvent` → renders signal card. On `AiEnrichmentEvent` → finds card by signalId, updates AI insight in-place. Reconnect handling with REST catch-up.

- [ ] **Step 4: Add scanner styles to main.css**

Signal card styles: green border for BUY, red for SELL. Badge styles for signal strength. Status bar styling. Filter control layout.

- [ ] **Step 5: Add navigation link to scanner from other pages**

Add "Scanner" link to the nav in dashboard.html, holdings.html, etc.

- [ ] **Step 6: Test manually in browser**

Start app: `./mvnw spring-boot:run`
Open: `http://localhost:8080/scanner.html`
Verify: page loads, WebSocket connects (check browser console), status bar shows.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/scanner.html src/main/resources/static/scripts/scanner.js src/main/resources/static/scripts/api.js src/main/resources/static/styles/main.css src/main/resources/static/dashboard.html src/main/resources/static/holdings.html
git commit -m "feat(scanner): add scanner.html with live WebSocket signal feed"
```

---

### Task 21: Demo Mode & Final Integration

**Files:**
- Create: `src/main/java/com/stockman/scanner/service/DemoSignalGenerator.java`
- Modify: `src/main/java/com/stockman/scanner/ScannerLifecycleManager.java`

- [ ] **Step 1: Implement DemoSignalGenerator**

Generates 3-5 hardcoded sample `TradeSignal` objects when demo mode is active. Publishes them as `SignalEvent` via the control-plane. Provides realistic-looking data for UI testing without Zerodha connection.

- [ ] **Step 2: Integrate demo mode into ScannerLifecycleManager**

On startup: if demo mode active (no Zerodha session), start `DemoSignalGenerator` instead of `TickerService`. WebSocket still active for demo signal push.

- [ ] **Step 3: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS

- [ ] **Step 4: Manual integration test**

1. Start app in demo mode: `./mvnw spring-boot:run`
2. Open `http://localhost:8080` → login with demo → navigate to scanner page
3. Verify: sample signals appear, WebSocket connected, filter controls work
4. Verify: status endpoint returns expected data at `GET /api/scanner/status`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/stockman/scanner/service/DemoSignalGenerator.java src/main/java/com/stockman/scanner/ScannerLifecycleManager.java
git commit -m "feat(scanner): add demo mode signal generator and final integration"
```

---

## Summary

| Phase | Tasks | Key Deliverable |
|-------|-------|-----------------|
| 1. Foundation | 1-5 | Config, models, events, calendar, registry |
| 2. Signal Engine | 6-10 | Candle builder, indicators, signal detection, AI enrichment |
| 3. Data Layer Services | 11-13 | TickerService, FundamentalCache, lifecycle manager |
| 4. Alert Service | 14-18 | Telegram, Discord, WebSocket, orchestrator |
| 5. REST API & Frontend | 19-21 | Controllers, scanner.html, demo mode |

**Total: 21 tasks, ~65 steps**
**Estimated new files: ~35**
**Estimated test files: ~10**
