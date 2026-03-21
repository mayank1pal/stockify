# Real-Time Market Scanner with Buy/Sell Signals & Alerting

**Date:** 2026-03-22
**Status:** Draft
**Scope:** v1 MVP
**Constraint:** Single-user application. The scanner assumes one active Zerodha session at a time.

## Overview

Add a real-time market scanner to StockMan that generates buy/sell signals with AI-enriched insights for Indian NSE F&O stocks. Targets active traders (scalping, intraday, swing) with alerts via Telegram, Discord, and an in-app live scanner page.

### Goals
- Stream real-time tick data from Zerodha WebSocket for user's holdings + custom watchlist
- Generate technical and momentum-based buy/sell signals across multiple timeframes
- Enrich signals with AI insights from existing multi-agent system (configurable by trading style)
- Deliver alerts via Telegram bot, Discord bot, and frontend WebSocket
- Maintain scanner as an in-process module with thread isolation (no microservices)

### Non-Goals (v1)
- Custom scan universes beyond holdings + watchlist (v2)
- News/sentiment-driven signals (v2)
- Index option chain scanning (v2)
- Backtesting / signal performance tracking (v3)
- Candlestick pattern recognition (v3)

---

## 1. Data Layer

### 1A. Real-time Price Feed — TickerService

**Purpose:** Stream real-time tick data from Zerodha's KiteTicker WebSocket into the app.

**Single-user session model:** The scanner uses the most recently authenticated Zerodha session. `ZerodhaService` exposes an `getActiveKiteConnect()` method that returns the last successfully authenticated `KiteConnect` instance. If multiple browser sessions exist, the scanner uses whichever authenticated most recently. Session changes trigger `ScanUniverseChangedEvent` to re-subscribe instruments.

**Ingress Pipeline:**
```
KiteTicker callback → immutable TickSnapshot → main ArrayBlockingQueue(10,000)
    → ticker-ingress thread dequeues → routes to per-worker ArrayBlockingQueue[4]
    → scanner-worker threads consume their own queues (partitioned by instrumentToken % 4)
```

- **KiteTicker callback** does minimal work: creates immutable `TickSnapshot` record, offers to main bounded queue, returns immediately. Drops on overflow with metric counter.
- **Two-tier queue structure** — single `ticker-ingress` thread dequeues from main queue and routes into one of 4 per-worker `ArrayBlockingQueue(2,500)` queues, partitioned by `instrumentToken % 4`. Each `scanner-worker` thread drains its own queue. This guarantees in-order processing per instrument while allowing parallel processing across instruments.
- **Instrument mapping** — `InstrumentRegistry` downloads Zerodha instrument CSV daily (~50MB). Maintains bidirectional maps: `symbol ↔ instrumentToken`, `ISIN ↔ instrumentToken`.
- **Tick staleness** — each `TickSnapshot` carries `receivedAt` timestamp. Consumers reject ticks older than 5 seconds. Cache entries marked stale after disconnect.
- **Re-subscription on reconnect** — single authoritative `AtomicReference<Set<Long>>` for subscribed instruments. Reconnect callback reads and re-subscribes + sets mode.
- **Market hours** — `ExchangeCalendar` abstraction with NSE holiday list (manual JSON, updated yearly). Connects at 9:00 AM IST for pre-open. Handles Muhurat/half-day sessions.
- **Overload policy** — on queue full: coalesce to latest tick per instrument (drop older). Metric counter for dropped ticks. Alert if drop rate exceeds threshold.

**Data model:**
```java
record TickSnapshot(
    long instrumentToken,
    double ltp,
    double open, double high, double low,
    double previousClose,      // renamed: this is PREVIOUS DAY close, not candle close
    long volume,
    double bidPrice, double askPrice,
    Instant exchangeTimestamp,
    Instant receivedAt
)
// Note: CandleBuilder uses `ltp` for intraday candle OHLCV construction.
// `previousClose` is the prior trading day's close (from Zerodha SDK), used for
// day-change % calculations. Do NOT confuse with intraday candle close price.
```

### 1B. Session Lifecycle — AuthStateManager

**States:** UNAUTHENTICATED → ACTIVE → DEGRADED

- **Pre-market validation** — at 9:00 AM, validates session token before connecting ticker. If expired, publishes `SessionExpiryEvent` → frontend shows re-auth prompt.
- **Mid-day expiry** — detects 403/token errors, transitions to DEGRADED. Stops generating signals. Notifies user via Telegram/Discord.
- **Reconnect with exponential backoff** — 5s → 10s → 20s → 40s → 60s max. Only reconnects if session is ACTIVE (not expired). **Market hours check:** do not attempt reconnection if current time is past `scanner.disconnect-time`.
- **Logout handling** — stops ticker gracefully, clears tick cache, cancels pending signals.
- **Backoff config** — all backoff parameters configurable via `scanner.reconnect.*` in application.yml.

### 1C. Fundamental Data Cache — FundamentalCacheService

**Sources (v1):** Finnhub API (best-effort, limited Indian market coverage on free tier) + Zerodha instrument CSV (primary source for 52-week high/low, lot size, exchange, segment). NSE scraping deferred to v2.

**Finnhub Indian market note:** Finnhub free tier has limited coverage for NSE stocks. Indian symbols may require `.NS` suffix and some fundamentals may be unavailable. The `FundamentalCacheService` treats Finnhub as best-effort: if data is unavailable for a symbol, fields are left null with `DataQuality.UNAVAILABLE`. The Zerodha instrument CSV is the reliable primary source for: `high52w`, `low52w`, `lotSize`, `exchange`, `segment`, `ISIN`.

- **Schedule** — `@Scheduled` at 7:00 AM IST, skips weekends + holidays via `ExchangeCalendar`.
- **Zerodha instruments** — primary source for 52-week data, lot size, exchange, segment from daily instrument CSV (~50MB, downloaded alongside `InstrumentRegistry` refresh).
- **Rate limiting** — Guava `RateLimiter.create(0.8)` (48 calls/min, under Finnhub's 60 limit). Batch with 1.25s gap.
- **Persistence** — atomic write to JSON file (write temp → rename). In-memory `ConcurrentHashMap`.
- **Staleness** — max 7-day threshold. Per-field nullability via `Double` wrappers (not primitive). `DataQuality` enum: FRESH / STALE / PARTIAL / UNAVAILABLE.

**Data model:**
```java
record FundamentalData(
    String symbol,
    Long instrumentToken,
    String isin,
    String exchange,       // NSE / BSE
    String sector,
    Integer lotSize,
    Double pe, Double eps, Double marketCap,
    Double high52w, Double low52w,
    Double dividendYield, Double bookValue,
    Double roe, Double debtToEquity,
    LocalDate lastUpdated,
    DataQuality quality    // FRESH, STALE, PARTIAL, UNAVAILABLE
)
```

### 1D. Event Architecture — Split Control-Plane vs Data-Plane

**Data-plane (hot path):**
- `ArrayBlockingQueue(10,000)` for tick transport
- Partitioned by instrument token
- In-order per instrument, bounded, drops on overflow
- ~1,000 ticks/sec throughput capacity

**Control-plane (Spring events):**
- `ApplicationEventPublisher` with named bounded `ThreadPoolTaskExecutor` (not default `SimpleAsyncTaskExecutor`)
- Carries: `SignalEvent`, `AiEnrichmentEvent`, `AlertEvent`, `SessionExpiryEvent`, `ScanUniverseChangedEvent`
- Low frequency (~10/sec max)

**Thread pools:**
| Pool | Threads | Purpose |
|------|---------|---------|
| `ticker-ingress` | 1 | Queue consumer, tick routing |
| `scanner-workers` | 4 | Partitioned indicator + signal computation |
| `alert-dispatch` | 8 (core), 16 (max), queue=500 | Telegram/Discord/WebSocket dispatch |
| `cache-refresh` | 1 | Daily fundamental refresh |
| `ai-enrichment` | 2 | Async AI calls via OpenRouter |

---

## 2. Signal Engine

### 2A. Core Architecture: Per-Symbol Serialized Pipeline

All steps execute atomically per symbol on a single partitioned worker thread:

```
1. Finalize candle → 2. Derive 5m/15m → 3. Update indicators → 4. Evaluate rules
→ 5. Transition state (CAS) → 6. Emit signal → 7. Enqueue AI (async)
```

- **Single-writer per symbol** — partitioned workers from Data Layer guarantee one thread per symbol at a time. No concurrent access to candle/indicator/signal state for the same instrument.
- **Barrier for higher timeframes** — 5m candle only finalizes after all 5 constituent 1m candles are committed. 15m after all 3 constituent 5m candles.
- **Previous-state retention** — each `IndicatorState` holds current + previous values for crossover detection. Updated atomically during step 3.

### 2B. Candle Builder

- **Event-time closing** — candle for minute N closes when first tick with timestamp ≥ N+1 arrives, OR heartbeat scheduler fires with 2-second grace period. Prevents GC/clock drift corruption.
- **Sliding window: 600 candles** per timeframe per instrument — EMA200 needs 500-600 candle burn-in for accuracy.
- **Empty candle handling** — if no tick arrives for a symbol during a minute, emit carry-forward candle (OHLC = previous close, volume = 0). Preserves indicator continuity.
- **VWAP volume spike filter** — first 5 minutes (9:15-9:20): ignore ticks where single-trade volume > 10× average to prevent block trade skew.
- **Silence period** — 9:15:00 to 9:16:30: candles built, signals suppressed. Allows order book to stabilize.
- **Warm-up mode** — first 30 minutes after market open: indicators computed but flagged as `WARMING_UP`. Signals suppressed until enough candles exist.
- **Memory** — ~200 instruments × 3 timeframes × 600 candles × ~120 bytes = ~43 MB.

**Data model:**
```java
record Candle(
    long instrumentToken,
    Instant openTime,
    double open, double high, double low, double close,
    long volume,
    double vwap
)
```

### 2C. Technical Indicators

**Trend:**
- EMA (9, 21, 50, 200) — Wilder smoothing, O(1) per candle
- SuperTrend — ATR via Wilder, O(1) after initialization
- VWAP — incremental, O(1), reset daily at 9:15 AM

**Momentum:**
- RSI (14) — Wilder smoothing, O(1)
- MACD (12,26,9) — EMA-based, O(1)
- Stochastic (14,3,3) — monotonic deque for rolling min/max, O(1) amortized

**Volume:**
- RVOL (Relative Volume) — replaces Delivery % (not available real-time in India)
- Volume spike — current vol > 2× 20-period SMA
- OBV — cumulative, O(1)

**Breakout:**
- Support/Resistance — pivot points from previous day OHLC, O(1)
- 52-week high/low breach — from fundamental cache, O(1)
- Opening Range Breakout (ORB) — first 15m high/low, available after 9:30 AM

**Computation timing:** Updated on candle close, not per tick. All O(1) per candle update.
**Stochastic noise fix:** On 1m timeframe, signals only valid if aligned with 15m EMA trend.
**NaN guard:** All computations wrapped with `Double.isFinite()`. NaN → indicator marked UNAVAILABLE, excluded from rules.

**Custom lightweight implementation** — no TA-Lib dependency. We only need ~10 indicators.

### 2D. Signal Detection

**Signal model (immutable):**
```java
record TradeSignal(
    String signalId,           // deterministic: symbol+style+timeframe+candleClose+direction
    String symbol, Long instrumentToken,
    SignalType type,           // BUY, SELL, STRONG_BUY, STRONG_SELL
    SignalStrength strength,   // WEAK, MODERATE, STRONG
    TradingStyle style,        // SCALP, INTRADAY, SWING
    double entryPrice, double stopLoss, double target,
    List<String> triggerReasons,
    IndicatorSnapshot indicators,
    Instant generatedAt
)
```

**AI enrichment (separate, keyed by signalId):**
```java
record AiEnrichment(
    String signalId,
    String insight,
    double aiConfidence,
    AgentType[] agentsUsed,
    Instant completedAt
)
```

**BUY rules:**
- EMA9 crosses above EMA21 + volume spike
- RSI crosses above 30 (oversold reversal)
- Price breaks above VWAP + OBV rising
- Opening range breakout (upside) + volume confirmation
- SuperTrend flips bullish

**SELL rules:**
- EMA9 crosses below EMA21 + volume spike
- RSI crosses below 70 (overbought reversal)
- Price breaks below VWAP + OBV falling
- Opening range breakout (downside) + volume confirmation
- SuperTrend flips bearish

**Multi-confirmation:** STRONG signals require 2+ indicators agreeing. WEAK from single indicator.
**Style routing:** Scalp = 1m candles, tight stops. Intraday = 5m candles, medium stops. Swing = 15m+ candles, wider stops.
**Stop-loss / target:** Auto-calculated via ATR. Minimum 1:2 risk-reward ratio.

### 2E. Signal Cooldown & State Machine

**Keyed by:** `symbol + style + timeframe` — a 1m scalp cooldown does NOT suppress a 15m swing signal.

**States:** NEUTRAL → BUY_ACTIVE / SELL_ACTIVE → COOLDOWN → NEUTRAL
**Direct reversal transition:** BUY_ACTIVE → SELL_ACTIVE (and vice versa) when opposite signal has strength ≥ MODERATE. Skips COOLDOWN.

**Transitions:** `AtomicReference<SignalState>` per key with compare-and-swap. No lost updates.

**Cooldown periods:**
- Scalp: 5 minutes
- Intraday: 15 minutes
- Swing: 60 minutes

**Rules:**
- Opposite signal bypasses cooldown only if strength ≥ MODERATE. WEAK reversals respect cooldown.
- Same-direction upgrade: MODERATE active + STRONG confirmation → emit upgrade event (not new signal).
- State expiry: lazy timestamp check on read. States older than 2× cooldown auto-expire to NEUTRAL. Periodic cleanup every 5 minutes.

### 2F. AI Enrichment

**Tiered by trading style:**
- **Scalp/Intraday** — signal fires immediately via `SignalEvent`. AI enrichment runs async, arrives as separate `AiEnrichmentEvent`. Frontend updates in-place by signalId.
- **Swing** — signal held for max 15 seconds. If AI responds, signal + enrichment emitted together. If timeout, signal emitted as `SignalEvent` with a wrapper field `aiPending=true` (note: `aiPending` is on the `SignalEvent` envelope, not on the immutable `TradeSignal` record).

**Global AI budgeter:**
- Priority queue: STRONG signals and SWING style get priority over WEAK/SCALP.
- Max 10 AI calls/minute via OpenRouter (Gemini 2.5 Flash BYOK, $0 cost).
- Stale AI detection: before applying result, verify signalId still matches current state. Discard if symbol has reversed.

**Agent selection:** New `ScannerAiService` bypasses the conversational `IntentClassifier` (which uses keyword-matching unsuitable for structured signal data). Instead, directly invokes TECHNICAL + FUNDAMENTAL agents via `OpenRouterModelService` with a purpose-built prompt template that takes `TradeSignal` + `IndicatorSnapshot` + `FundamentalData` as structured input. Does NOT go through `OrchestratorService`'s conversational pipeline.

**Fallback:** If AI call fails or times out, signal shows without insight. Never blocks scalp/intraday delivery.

---

## 3. Alert Service

### 3A. Alert Delivery State Machine

Per `signalId × channel` tracking with atomic CAS transitions:

**States:** NEW → SENT (messageRef stored) → ENRICHED / FAILED / STALE

- **Pending enrichment buffer** — if `AiEnrichmentEvent` arrives while state is NEW (message not yet sent), buffer in Caffeine cache (20s TTL — exceeds swing gate timeout of 15s). Retry edit once state transitions to SENT.
- **Atomic transitions** — `AtomicReference<DeliveryState>` per key. No lost updates between send and edit threads.
- **Persistence** — in-memory for v1 (lost on restart, acceptable). If edit fails post-restart, send fresh message instead.

### 3B. AlertOrchestrator

- **Executor** — `ThreadPoolTaskExecutor`: core=8, max=16, queue=500.
- **Rejection policy** — custom `DiscardWithMetricPolicy`: logs dropped alert, increments counter, never stalls scanner pipeline.
- **Stale alert dropping** — before dispatch, check signal age. Drop if: scalp >30s, intraday >60s, swing >300s.

**Tiered error handling:**
| Error Level | Example | Action |
|-------------|---------|--------|
| Auth (401) | Invalid bot token | Circuit-break channel. Notify user via other channels. |
| Channel (404) | Discord channel deleted | Disable that style's channel mapping only. |
| Message (404) | Telegram message deleted | Mark STALE. Send fresh message for AI enrichment. |
| Rate (429) | API rate limit | Honor server `retry_after`. No custom rate limiter on top of library. |

### 3C. Telegram Bot

- **Library:** java-telegram-bot-api (pengrad)
- **Rate limiting:** honor Telegram's 429 `retry_after`. Local soft limit: 1 msg/sec per chat (60/min), burst capacity of 5.
- **Message format:** rich HTML with signal details (entry/stop/target, R:R ratio, trigger reasons, trading style, timeframe).
- **AI enrichment:** `editMessageText` to update original alert. If edit fails (message deleted), send fresh message.
- **Commands:** `/start`, `/stop`, `/status`, `/style scalp|intraday|swing`, `/strength weak|moderate|strong`
- **Config:** `alerts.telegram.bot-token`, `alerts.telegram.chat-id` from env vars.

### 3D. Discord Bot

- **Library:** JDA (Java Discord API)
- **Rate limiting:** let JDA handle natively (reads Discord's route-specific response headers). No custom Guava limiter.
- **Message format:** color-coded embeds (green=BUY, red=SELL). Fields: entry/stop/target, triggers, AI insight.
- **Channel structure:** separate channels per trading style (`#scalp-signals`, `#intraday-signals`, `#swing-signals`). Configurable channel IDs.
- **AI enrichment:** edit embed to add "AI Insight" field.
- **Slash commands:** `/status`, `/style`, `/strength`
- **Config:** `alerts.discord.bot-token`, `alerts.discord.channel-ids` map from env vars.

### 3E. Frontend WebSocket

- **Protocol:** Spring WebSocket with STOMP over SockJS.
- **STOMP destinations:**
  - `/topic/signals` — new `TradeSignal` events
  - `/topic/ai-enrichment` — `AiEnrichment` updates (matched by signalId)
  - `/topic/scanner-status` — scanner health (connected, instrument count, last tick)

**Security hardening:**
- `HandshakeInterceptor` validates HTTP session on upgrade.
- `setAllowedOrigins()` locked to configured CORS origins.
- `ChannelInterceptor` on inbound: SUBSCRIBE allowed only to defined topics, SEND denied (server-push only), CONNECT validates session.
- SockJS iframe transport disabled.
- Independently toggleable: `alerts.websocket.enabled` (for headless deployments).

**Reconnection:** SockJS auto-reconnect. Client requests missed signals via REST catch-up endpoint.

### 3F. Scanner Page — scanner.html

New frontend page in StockMan's vanilla JS + static resources:
- **Live signal feed** — newest first, scrollable, auto-updates via WebSocket
- **Per-signal card** — entry/stop/target, indicator badges, AI insight (updates in-place by signalId)
- **Filter controls** — trading style, signal strength, specific symbols
- **Scanner status bar** — connection status, instruments tracked, signals generated today
- **Indicator panel** — click a signal to expand and see full indicator snapshot

### 3G. Signal History & Catch-up

- **Bounded ring buffer** — last 500 signals in `ConcurrentLinkedDeque` with eviction. ~1 MB.
- **Cursor-based pagination** — `GET /api/scanner/signals?cursor={signalId}&limit=50`. Cursor is last-seen signalId.
- **Enrichment co-stored** — AI enrichment attached to signal in buffer when it arrives.
- **Retention** — signals older than current trading day evicted at market close.

---

## 4. REST API

### Scanner Endpoints (user auth)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/scanner/status` | GET | Scanner health + alert channel health |
| `/api/scanner/signals` | GET | Cursor-paginated signals (filterable by style/strength/symbol) |
| `/api/scanner/signals/{signalId}` | GET | Single signal + AI enrichment |
| `/api/scanner/indicators/{symbol}` | GET | Current indicator snapshot |

### User Preference Endpoints (user auth)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/user/watchlist` | GET/PUT | Custom scan universe. PUT → ScanUniverseChangedEvent |
| `/api/user/alerts/preferences` | GET/PUT | Alert filter preferences (style, strength) |

### Admin Endpoints (admin auth)
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/admin/alerts/config` | GET/PUT | Channel config (tokens, channel IDs). Audit logged. Secrets masked in GET. |

---

## 5. Configuration

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
```

**Validation:** `@Validated` + JSR-303 on alert config beans. If `enabled=true` and token is blank → startup fails with clear error.

---

## 6. New Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| java-telegram-bot-api | Telegram bot integration | 7.x |
| JDA | Discord bot integration | 5.x |
| spring-boot-starter-websocket | STOMP/SockJS support | (Spring Boot managed) |
| caffeine | Pending enrichment buffer, caching | 3.x |
| guava | RateLimiter for Finnhub API | 33.x |

---

## 7. Memory Budget

| Component | Estimate |
|-----------|----------|
| Candle buffers (200 × 3 × 600 × 120B) | ~43 MB |
| Tick cache (200 instruments × latest tick) | ~0.1 MB |
| Indicator state (200 instruments × 3 timeframes) | ~2 MB |
| Signal history (500 signals × ~2 KB) | ~1 MB |
| Fundamental cache (200 stocks) | ~0.5 MB |
| Alert delivery state | ~0.1 MB |
| **Total scanner overhead** | **~47 MB** |

Comfortable within a 512 MB JVM heap alongside existing StockMan services.

---

## 8. Risk & Mitigations

| Risk | Mitigation |
|------|------------|
| Zerodha session expires mid-day | AuthStateManager → DEGRADED mode, notify user |
| Finnhub API unavailable | Serve stale cache (max 7 days), log warning |
| AI enrichment overloaded | Priority queue, budget cap, graceful fallback |
| Market volatility spike (50+ signals/min) | Bounded queues, stale alert dropping, cooldown |
| Telegram/Discord API down | Independent dispatch, circuit breaker per channel |
| Cold start (no candles/indicators) | 30-min warm-up mode, signals suppressed |
| Clock drift affecting candle close | Event-time closing with grace period |

---

## 9. Additional Specifications

### 9A. Demo Mode Behavior

The existing demo mode (`POST /api/auth/demo`) creates a mock session without Zerodha credentials. Scanner behavior in demo mode:
- **Scanner page accessible** — shows UI with "Demo Mode" banner
- **No real-time ticks** — `TickerService` does not connect (no Zerodha session)
- **Sample signals** — `DemoSignalGenerator` produces 3-5 hardcoded sample signals on page load so users can see the UI and alert format
- **Alert channels disabled** — Telegram/Discord bots not triggered in demo mode
- **WebSocket active** — pushes demo signals for testing the live feed

### 9B. Watchlist Management

**Data model:**
```java
record Watchlist(
    Set<String> symbols,       // NSE symbols, e.g., "RELIANCE", "TCS"
    Instant lastModified
)
```

- **Persistence** — JSON file at `data/watchlist.json` (atomic write via temp + rename). Loaded into memory at startup.
- **Validation** — max 200 symbols. Must exist in `InstrumentRegistry` (valid NSE F&O instrument). Invalid symbols rejected with 400 error.
- **ScanUniverseChangedEvent** — `PUT /api/user/watchlist` publishes this event → `TickerService` re-subscribes to updated instrument set.
- **Default** — user's Zerodha holdings symbols (auto-populated on login).

### 9C. Graceful Shutdown

`@PreDestroy` ordering on `ScannerLifecycleManager`:
1. Stop `TickerService` — disconnect KiteTicker WebSocket
2. Drain `ticker-ingress` queue (max 2s timeout)
3. Shutdown `scanner-workers` pool (graceful, await 5s)
4. Shutdown `ai-enrichment` pool (graceful, await 5s)
5. Shutdown `alert-dispatch` pool (graceful, await 10s — allow pending Telegram/Discord sends)
6. Disconnect Telegram bot + JDA client
7. Stop STOMP broker
8. Persist watchlist and fundamental cache to disk

### 9D. Metrics & Observability

**Framework:** Micrometer (Spring Boot Actuator, already available).

**Key metrics:**
| Metric | Type | Description |
|--------|------|-------------|
| `scanner.ticks.received` | Counter | Total ticks received from WebSocket |
| `scanner.ticks.dropped` | Counter | Ticks dropped due to queue overflow |
| `scanner.ticks.stale` | Counter | Ticks rejected as stale (>5s) |
| `scanner.signals.generated` | Counter (tagged by style, strength) | Signals generated |
| `scanner.signals.dropped.stale` | Counter | Signals dropped before alert dispatch |
| `scanner.alerts.sent` | Counter (tagged by channel) | Alerts successfully sent |
| `scanner.alerts.failed` | Counter (tagged by channel, error_level) | Alert failures |
| `scanner.alerts.dropped` | Counter | Alerts dropped by executor rejection |
| `scanner.ai.calls` | Counter | AI enrichment calls made |
| `scanner.ai.timeouts` | Counter | AI enrichment timeouts |
| `scanner.queue.size` | Gauge | Current main tick queue depth |

**Threshold alerting:** If `scanner.ticks.dropped` rate exceeds 10/sec for 30s, log WARN. Exposed via `/api/scanner/status`.

### 9E. Security for Admin Endpoints

**Current state:** StockMan has no Spring Security or role-based access. For v1:
- Admin endpoints (`/api/admin/**`) protected by a simple API key header (`X-Admin-Key`) validated against `admin.api-key` config property.
- Secrets (bot tokens) never returned in GET responses — masked as `***`.
- All PUT operations to admin endpoints audit-logged (log level INFO with timestamp, endpoint, changed fields).
- **Future:** Migrate to Spring Security with role-based access when multi-user support is added.

### 9F. WebSocket CORS Configuration

WebSocket endpoints require separate CORS config from REST endpoints:
- `WebSocketMessageBrokerConfigurer.registerStompEndpoints()` uses `setAllowedOriginPatterns()` matching the same origins as `app.cors.allowed-origins`.
- This is a separate code path from the existing `WebConfig` CORS setup on `/api/**`.
- SockJS fallback endpoints inherit the same origin restrictions.
