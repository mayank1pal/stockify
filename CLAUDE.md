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

**Required environment variables:** `ZERODHA_API_KEY`, `ZERODHA_API_SECRET`, `GEMINI_API_KEY`

## Architecture

Spring Boot 3.2.2 (Java 17) REST API with a vanilla JS frontend served as static resources.

**Backend** (`src/main/java/com/stockman/`):
- **controller/** — REST endpoints: `AuthController` (OAuth with Zerodha), `PortfolioController` (holdings/positions), `AnalysisController` (AI-powered stock analysis)
- **service/** — `ZerodhaService` (broker API integration), `GeminiService` (Google Gemini AI calls), `StockAnalyzer` (analysis orchestration), `PortfolioService` (portfolio calculations)
- **model/** — DTOs (Holding, Position, PortfolioSummary, StockInsight, AnalysisRequest/Response, etc.)
- **prompts/** — AI prompt templates (`AnalysisPrompts`, `AgentPrompts`) for five analyst agent types: FUNDAMENTAL, TECHNICAL, QUANTITATIVE, SENTIMENT, GENERAL
- **config/** — `ZerodhaConfig`, `GeminiConfig`, `WebConfig` (CORS + static resources)

**Frontend** (`src/main/resources/static/`):
- Pages: index.html, dashboard.html, holdings.html, analysis.html, strategy.html
- Scripts: api.js (backend communication via Fetch API), app.js (DOM logic)
- Styles: main.css

## Key Patterns

- **Lombok everywhere**: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` on all classes
- **Constructor injection**: No field injection; Spring wires via `@RequiredArgsConstructor`
- **Session management**: HTTP session + `ConcurrentHashMap<String, AuthSession>` for Zerodha access tokens (24h timeout)
- **Demo mode**: `POST /api/auth/demo` enables full app with hardcoded sample data — no Zerodha credentials needed
- **Config externalized**: All secrets via env vars in `application.yml`, never hardcoded
- **Error handling**: Try-catch in services with fallback responses; controllers return appropriate HTTP status codes

## External Integrations

1. **Zerodha Kite Connect** (v3.3.1) — OAuth flow for authentication, fetches real-time holdings/positions
2. **Google Gemini API** — AI analysis via HTTP (WebFlux WebClient), model configurable via `gemini.model` property (default: `gemini-pro`)
