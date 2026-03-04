# Investment Copilot: Multi-Model Agentic Orchestration

**Date:** 2026-03-04
**Status:** Approved

## Summary

Transform StockMan from a basic AI analysis tool into a full investment copilot with an adaptive orchestrator that dynamically routes queries to specialized agents powered by both Google Gemini and Anthropic Claude, with reasoning chains, cross-model validation, and actionable trade plans.

## Architecture: Orchestrator-as-Service

An `OrchestratorService` acts as the central brain. It receives any user query, classifies intent, selects which agents to invoke (and on which model), runs them in parallel or sequentially, then synthesizes results via a dedicated Synthesizer agent.

```
User Query --> Orchestrator --> [Intent Classifier]
                                      |
                    +---------+-------+-------+---------+
                    |         |       |       |         |
                 Agent A   Agent B  Agent C  Agent D  Agent E
                (Claude)  (Gemini) (Claude) (Gemini) (Claude)
                    |         |       |       |         |
                    +---------+-------+-------+---------+
                                      |
                              Synthesizer Agent
                                  (Claude)
                                      |
                              Final Response
                     (recommendation + reasoning chain
                      + trade plan + confidence score)
```

## Agent Lineup (10 Total)

### Existing Agents (upgraded with model abstraction)

| Agent | Purpose | Default Model |
|-------|---------|---------------|
| Fundamental | Company financials, valuations, growth metrics | Gemini |
| Technical | Chart patterns, support/resistance, indicators | Gemini |
| Quantitative | Statistical analysis, correlations, probabilities | Gemini |
| Sentiment | Market mood, investor behavior, social signals | Gemini |
| General | Broad market questions, educational queries | Gemini |

### New Agents

| Agent | Purpose | Default Model | Rationale |
|-------|---------|---------------|-----------|
| Risk Assessor | Downside scenarios, volatility, correlation risks | Claude | Strong nuanced risk reasoning |
| Portfolio Optimizer | Rebalancing, allocation targets, diversification | Gemini | Fast structured numerical output |
| Geopolitical Analyst | Wars, tariffs, sanctions, trade policy impact | Claude | Multi-factor geopolitical reasoning |
| Trade Executor | Actionable trade plans: entry/exit/stop-loss levels | Claude | Precise reasoning for trade parameters |
| Synthesizer | Merges multi-agent outputs into unified recommendation | Claude | Coherent summarization across sources |

## AI Model Abstraction Layer

### Unified Interface

```java
public interface AIModelService {
    String getName();                    // "gemini" or "claude"
    CompletableFuture<String> analyze(String systemPrompt, String userPrompt);
    boolean isAvailable();               // health check
}
```

### Implementations

- `GeminiModelService` — refactored from existing `GeminiService`, uses WebClient to Gemini API
- `ClaudeModelService` — new, uses WebClient to Anthropic Messages API (`https://api.anthropic.com/v1/messages`)
  - Model: `claude-sonnet-4-6`
  - New env var: `ANTHROPIC_API_KEY`

### Agent Definition

```java
@Data @Builder
public class AgentDefinition {
    private AgentType type;
    private String name;
    private String systemPrompt;
    private String preferredModel;    // "claude" or "gemini"
    private String fallbackModel;     // if preferred is unavailable
    private List<String> focusAreas;
    private List<String> suggestedQuestions;
    private String icon;
}
```

## Orchestrator Design

### Intent Classifier (rule-based v1)

Maps user queries to agent combinations:

| Query Pattern | Agents Invoked |
|---------------|----------------|
| "Should I buy {stock}?" | Fundamental + Technical + Risk Assessor + Trade Executor |
| "How are tariffs/wars affecting my portfolio?" | Geopolitical Analyst + Portfolio Optimizer |
| "Rebalance my portfolio" | Portfolio Optimizer + Risk Assessor + Trade Executor |
| "Full analysis of {stock}" | All specialist agents -> Synthesizer |
| "What's the risk of {stock}?" | Risk Assessor + Technical + Quantitative |
| General question | General agent only |

### Agent Router

- Dispatches to selected agents using `CompletableFuture` for parallel execution
- Per-agent timeout: 30 seconds
- If an agent fails/times out, it's skipped and noted in reasoning chain
- Capped at 6 concurrent agent calls

### Cross-Model Validation

When both Gemini and Claude analyze the same stock, the Synthesizer compares conclusions:
- Agreement = high confidence score
- Disagreement = flagged with both perspectives presented

### Response Structure

```json
{
  "recommendation": "BUY/SELL/HOLD",
  "confidence": 0.85,
  "reasoning_chain": [
    {"agent": "Fundamental", "model": "gemini", "finding": "...", "confidence": 0.8},
    {"agent": "Risk Assessor", "model": "claude", "finding": "...", "confidence": 0.9}
  ],
  "trade_plan": {
    "entry": 2450,
    "target": 2680,
    "stop_loss": 2350,
    "position_size": "5% of portfolio",
    "timeframe": "2-4 weeks"
  },
  "cross_model_agreement": true,
  "dissenting_views": [],
  "agents_used": ["fundamental", "technical", "risk_assessor", "trade_executor"],
  "agents_skipped": [],
  "timestamp": "2026-03-04T10:30:00Z"
}
```

## Market Data Sources

### Zerodha Kite Connect (existing, expanded usage)

| Data | API Endpoint | Status |
|------|-------------|--------|
| Holdings & Positions | `/portfolio/holdings`, `/portfolio/positions` | Already integrated |
| Real-time Quotes (LTP, OHLC, volume) | `/quote?i=NSE:{symbol}` | New |
| Historical Candles (daily OHLC) | `/instruments/historical/{token}/{interval}` | New |
| Market Depth (bid/ask) | `/quote` (includes depth data) | New |
| Instrument List | `/instruments` | New |

### Finnhub (new integration)

- **Purpose:** News headlines, company news, market news, basic sentiment
- **Free tier:** 60 calls/min
- **Env var:** `FINNHUB_API_KEY`
- **Key endpoint:** `GET /api/v1/company-news?symbol={symbol}&from={date}&to={date}`
- **Feeds into:** Geopolitical Analyst, Sentiment agent, News context for all agents

### Enhanced Prompt Context

Agents receive real market data in prompts:

```
Current Market Data:
- LTP: Rs.2,456.80 | Day Change: +1.2%
- 52-week: Rs.2,180 - Rs.2,890
- Volume: 4.2M (avg: 3.8M)
- Last 30 days: +5.4% | Last 90 days: -2.1%

Recent News:
- "Reliance Jio reports 15% YoY revenue growth" (2 days ago)
- "Mukesh Ambani announces $10B green energy investment" (5 days ago)

Holdings Data:
- Qty: 15 | Avg Price: Rs.2,380 | P&L: +3.2%
```

## New API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/copilot/ask` | POST | Main entry — orchestrator routes user query |
| `/api/copilot/agents` | GET | Returns all 10 agents with metadata |
| `/api/copilot/analyze/{symbol}` | POST | Full orchestrated stock analysis |
| `/api/copilot/portfolio-review` | POST | Full portfolio review with all relevant agents |
| `/api/copilot/reasoning/{requestId}` | GET | Fetch reasoning chain for past analysis |

Existing `/api/analysis/*` endpoints remain for backwards compatibility.

## Frontend: New Copilot Page

**New page:** `copilot.html` — primary interface for the investment copilot

### Features
- Conversational interface: user asks anything, sees orchestrated response
- Reasoning chain visualization: expandable cards showing each agent's contribution, model used, confidence
- Cross-model agreement indicator: visual badge (green = agreement, amber = partial, red = divergent)
- Trade plan cards: entry/exit/stop-loss displayed prominently with position sizing
- Agent activity indicator: shows which agents are running in real-time

## Error Handling & Fallbacks

### Model Fallbacks
- If Claude is down -> all agents fall back to Gemini
- If Gemini is down -> all agents fall back to Claude
- If both are down -> return cached last analysis or graceful error message

### Agent Resilience
- Per-agent timeout: 30 seconds
- Failed/timed-out agents are skipped and noted in reasoning chain
- Synthesizer works with whatever agents succeeded (graceful degradation)
- Each agent returns structured `AgentResult` (success/failure + partial data)

### Cost Control
- Simple queries (single agent) -> 1 model call
- Medium queries (2-3 agents) -> 2-3 model calls
- Full analysis (all agents) -> parallel, capped at 6 concurrent
- Response caching: same query within 15 minutes returns cached result
- In-memory cache via `ConcurrentHashMap` (no Redis for v1)

### Global Error Handling
- `@ControllerAdvice` global exception handler for clean HTTP error responses
- Input validation with `@Valid` annotations on request DTOs

## New Configuration

```yaml
# application.yml additions
anthropic:
  api-key: ${ANTHROPIC_API_KEY:placeholder}
  model: claude-sonnet-4-6
  base-url: https://api.anthropic.com/v1

finnhub:
  api-key: ${FINNHUB_API_KEY:placeholder}
  base-url: https://finnhub.io/api/v1

copilot:
  agent-timeout-seconds: 30
  max-concurrent-agents: 6
  cache-ttl-minutes: 15
```

## New Environment Variables

| Variable | Purpose |
|----------|---------|
| `ANTHROPIC_API_KEY` | Claude API access |
| `FINNHUB_API_KEY` | News data access |

## File Changes Summary

### New Files
- `service/AIModelService.java` — unified AI interface
- `service/ClaudeModelService.java` — Claude API integration
- `service/FinnhubService.java` — news data integration
- `service/OrchestratorService.java` — adaptive orchestrator
- `service/IntentClassifier.java` — query -> agent mapping
- `service/MarketDataService.java` — enriched market data (quotes + history from Zerodha)
- `model/AgentDefinition.java` — agent configuration DTO
- `model/AgentResult.java` — individual agent output
- `model/OrchestratorResponse.java` — full copilot response
- `model/CopilotRequest.java` — copilot input DTO
- `model/MarketData.java` — enriched market data DTO
- `model/NewsArticle.java` — news article DTO
- `controller/CopilotController.java` — new copilot endpoints
- `config/AnthropicConfig.java` — Claude configuration
- `config/FinnhubConfig.java` — Finnhub configuration
- `prompts/CopilotPrompts.java` — new agent prompts (Risk, Geopolitical, Trade Executor, etc.)
- `exception/GlobalExceptionHandler.java` — controller advice
- `static/copilot.html` — new copilot page
- `static/scripts/copilot.js` — copilot UI logic

### Modified Files
- `service/GeminiService.java` — refactor to implement `AIModelService`
- `service/ZerodhaService.java` — add quote/historical data methods
- `config/WebConfig.java` — add copilot page routing
- `application.yml` — add anthropic, finnhub, copilot config sections
- `static/scripts/api.js` — add copilot API methods
- `static/styles/main.css` — add copilot page styles
- Navigation in all HTML pages — add Copilot link to sidebar
