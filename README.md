# Stockify

Portfolio analysis and a real-time market scanner, built on top of Zerodha Kite Connect and a multi-agent LLM investment copilot.

## What it does

- **Portfolio & holdings** — pulls live holdings and positions from your Zerodha account (or runs entirely on demo data, no broker account needed)
- **Investment Copilot** — ask free-text questions ("should I buy X", "how should I rebalance") and get answers from a panel of specialist AI agents (fundamental, technical, quantitative, sentiment, risk, portfolio-optimizer, geopolitical, trade-executor) whose findings are synthesized into one recommendation with a confidence score and cost breakdown
- **Legacy single-agent analysis** — simpler per-symbol analysis (fundamental/technical/quantitative/sentiment/general) for quick lookups
- **Real-time scanner** — subscribes to live ticks, builds candles, computes indicators, and pushes trade signals over WebSocket, Telegram, and Discord as they fire

## Tech stack

- **Backend**: Spring Boot 3.2.2, Java 17
- **Frontend**: vanilla JS, served as static resources (no build step)
- **Broker integration**: Zerodha Kite Connect (OAuth + WebSocket ticks)
- **LLM gateway**: OpenRouter (all model calls go through one client; per-agent model selection is config-driven)
- **News/sentiment**: Finnhub
- **Alerting**: Telegram bot API, Discord (JDA), STOMP over WebSocket

## Getting started

### Prerequisites

- Java 17
- A [Zerodha Kite Connect](https://kite.trade/) app (API key/secret) — or skip this and use demo mode
- An [OpenRouter](https://openrouter.ai/) API key
- A [Finnhub](https://finnhub.io/) API key

### Environment variables

Required:

```
ZERODHA_API_KEY
ZERODHA_API_SECRET
OPENROUTER_API_KEY
FINNHUB_API_KEY
ADMIN_API_KEY
```

Optional (defaulted or feature-gated):

```
ZERODHA_REDIRECT_URL
CORS_ALLOWED_ORIGINS
TELEGRAM_BOT_TOKEN
DISCORD_BOT_TOKEN
DISCORD_SCALP_CHANNEL / DISCORD_INTRADAY_CHANNEL / DISCORD_SWING_CHANNEL
```

### Run

```bash
./mvnw spring-boot:run
```

App runs on `http://localhost:8080`. If you don't have Zerodha/API credentials handy, hit `POST /api/auth/demo` (or use the demo-mode button in the UI) to explore the app with sample data.

### Build & test

```bash
./mvnw clean install              # Build
./mvnw clean install -DskipTests  # Build without tests
./mvnw test                       # Run all tests
./mvnw test -Dtest=ClassName      # Run a single test class
```

## Project layout

```
src/main/java/com/stockman/
├── controller/   REST endpoints (auth, portfolio, analysis, copilot, scanner, admin)
├── service/      Zerodha integration, OpenRouter LLM calls, intent classification, orchestration
├── model/        DTOs
├── prompts/      AI prompt templates for both agent systems
├── config/       Zerodha/OpenRouter/Finnhub/CORS/WebSocket/scanner config
└── scanner/      real-time tick → candle → indicator → signal → alert pipeline

src/main/resources/static/   vanilla JS frontend (dashboard, holdings, analysis, copilot, scanner)
docs/                        design specs and implementation plans
```

See [CLAUDE.md](CLAUDE.md) for a deeper architecture walkthrough.
