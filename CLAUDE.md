# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
./mvnw clean install              # Build project
./mvnw clean install -DskipTests  # Build without tests
./mvnw test                       # Run all tests
./mvnw test -Dtest=ClassName      # Run single test class
./mvnw test -Dtest=ClassName#method  # Run single test method
./mvnw spring-boot:run            # Run app on port 8080
```

**Required environment variables:** `ZERODHA_API_KEY`, `ZERODHA_API_SECRET`, `OPENROUTER_API_KEY`, `FINNHUB_API_KEY`, `ADMIN_API_KEY`

Optional (have defaults or are feature-gated): `ZERODHA_REDIRECT_URL`, `CORS_ALLOWED_ORIGINS`, `TELEGRAM_BOT_TOKEN`, `DISCORD_BOT_TOKEN`, `DISCORD_SCALP_CHANNEL`/`DISCORD_INTRADAY_CHANNEL`/`DISCORD_SWING_CHANNEL`.

## Architecture

Spring Boot 3.2.2 (Java 17) REST API with a vanilla JS frontend served as static resources.

**Backend** (`src/main/java/com/stockman/`):
- **controller/** — `AuthController` (Zerodha OAuth), `PortfolioController` (holdings/positions), `AnalysisController` (legacy single-agent analysis), `CopilotController` (multi-agent investment copilot), `ScannerController` (scanner status/signals/indicators), `UserPreferenceController`, `AdminController` (protected by `ADMIN_API_KEY`)
- **service/** — `ZerodhaService` (broker API integration), `OpenRouterModelService` (implements `AIModelService`; all LLM calls go through OpenRouter), `IntentClassifier` (keyword-based routing to copilot agents), `OrchestratorService` (parallel agent dispatch + synthesis), `StockAnalyzer` (legacy analysis orchestration), `PortfolioService`, `MarketDataService`, `FinnhubService` (news)
- **model/** — DTOs (Holding, Position, PortfolioSummary, StockInsight, CopilotRequest, OrchestratorResponse, AgentResult, LlmUsage, CostMetadata, etc.)
- **prompts/** — `AnalysisPrompts`/`AgentPrompts` (legacy 5-agent system), `CopilotPrompts` (10-agent copilot system, includes the synthesizer prompt)
- **config/** — `ZerodhaConfig`, `OpenRouterConfig`, `FinnhubConfig`, `WebConfig` (CORS + static resources), `WebSocketConfig` (STOMP broker for scanner alerts), `AlertConfig`, `ScannerConfig`, `AsyncConfig`
- **scanner/** — real-time market scanner subsystem (see below)

**Frontend** (`src/main/resources/static/`):
- Pages: index.html, dashboard.html, holdings.html, analysis.html, strategy.html, copilot.html, scanner.html
- Scripts: api.js (backend communication via Fetch API), app.js/copilot.js/scanner.js (page-specific DOM logic)
- Styles: main.css

## Two parallel analysis systems

The codebase has two independent AI analysis paths that both call into the LLM layer but differ in shape:

1. **Legacy** — `AnalysisController` → `StockAnalyzer` → `AgentPrompts`/`AnalysisPrompts`. Single agent type selected per request (FUNDAMENTAL/TECHNICAL/QUANTITATIVE/SENTIMENT/GENERAL), synchronous, no cross-model synthesis.
2. **Copilot** — `CopilotController` → `OrchestratorService`. A free-text query is run through `IntentClassifier` (keyword matching) to select 1+ of 10 agent types (adds RISK_ASSESSOR, PORTFOLIO_OPTIMIZER, GEOPOLITICAL, TRADE_EXECUTOR, SYNTHESIZER). Agents run in parallel on a fixed thread pool with per-agent timeout; if 2+ agents succeed, their findings are merged by a SYNTHESIZER agent call into one recommendation with confidence, dissenting views, and cost metadata. Responses are cached in-memory by `query|symbol` with a TTL, and a bounded LRU (`responseHistory`, max 200) supports fetching a past reasoning chain by request ID.

Both paths short-circuit to hardcoded demo data when `session.getAttribute("demoMode")` is true (set via `POST /api/auth/demo`).

## Real-time scanner (`scanner/` package)

An event-driven pipeline that ingests ticks and emits trade signals, independent of the analysis systems above:

`TickerService` (Zerodha WS ticks) → `ScannerPipeline` → `CandleBuilder` (builds 1m candles) → `IndicatorEngine` (updates indicator snapshot on candle close) → `SignalDetector` (evaluates current vs. previous snapshot) → `SignalCooldownManager` (dedupes by symbol/style/timeframe/signal type) → publishes `SignalEvent` via Spring's `ApplicationEventPublisher`.

`AlertOrchestrator` listens for `SignalEvent`/`AiEnrichmentEvent` (`@Async("alertExecutor")`) and does tiered delivery: WebSocket (STOMP, always), then Telegram, then Discord — tracking per-channel `AlertDeliveryState` so later AI enrichments can edit the original alert message instead of resending. Stale signals (>60s old) are dropped before delivery.

`ScannerLifecycleManager` (`@PostConstruct`) decides at startup whether to run live (`TickerService`) or fall back to `DemoSignalGenerator`, based on: scanner enabled flag, an active Zerodha session, `ExchangeCalendar` trading-day/holiday check (`holidays.json`), and current time vs. configured market hours.

Scanner behavior (session times, cooldowns, reconnect backoff, AI enrichment budget) is fully driven by the `scanner:` block in `application.yml` — check there before touching scanner code, since most "constants" are actually config.

## Key Patterns

- **Lombok everywhere**: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` on all classes
- **Constructor injection**: no field injection; most classes wire via `@RequiredArgsConstructor`. A few (`ZerodhaService`, `OrchestratorService`) use explicit constructors instead — needed wherever `@Qualifier` is involved, since Lombok's generated constructor doesn't propagate `@Qualifier` annotations
- **`@ConfigurationProperties` over `@Value` for maps**: `OpenRouterConfig` (`prefix = "openrouter"`, `@Configuration(proxyBeanMethods = false)` + `@Getter/@Setter`) binds the `openrouter.models` agent→model map; inject `OpenRouterConfig` and call `getModels()` at runtime rather than exposing the map via a separate `@Bean`
- **Session management**: HTTP session for demo-mode/auth flags + `ConcurrentHashMap<String, AuthSession>` / `ConcurrentHashMap<String, KiteConnect>` in `ZerodhaService` keyed by session ID (24h expiry)
- **Demo mode**: `POST /api/auth/demo` enables full app with hardcoded sample data — no Zerodha credentials needed
- **Config externalized**: all secrets via env vars in `application.yml`, never hardcoded
- **Error handling**: `GlobalExceptionHandler` (`@RestControllerAdvice`) catches validation and unhandled exceptions app-wide; services also try-catch with fallback responses at the call site

## External Integrations

1. **Zerodha Kite Connect** (v3.3.1) — OAuth flow for authentication, fetches real-time holdings/positions, and provides the tick stream for the scanner
2. **OpenRouter** — single unified gateway for all LLM calls (`OpenRouterModelService`); per-agent model selection lives in `openrouter.models` in `application.yml`, not in code
3. **Finnhub** — news/sentiment data feed
4. **Telegram / Discord** — outbound scanner alert channels (feature-gated independently via `alerts.telegram.enabled` / `alerts.discord.enabled`)
