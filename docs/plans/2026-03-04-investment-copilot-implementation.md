# Investment Copilot Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a multi-model agentic investment copilot with adaptive orchestration, powered by Gemini + Claude, with real-time market data from Zerodha and news from Finnhub.

**Architecture:** Orchestrator-as-Service pattern. An `OrchestratorService` classifies user intent, routes to specialized agents (10 total) running on the best-fit AI model, collects results in parallel via `CompletableFuture`, and synthesizes via a dedicated Synthesizer agent. Exposed through a new `/api/copilot/*` REST namespace and a dedicated frontend page.

**Tech Stack:** Spring Boot 3.2.2, Java 17, WebClient (Gemini + Claude + Finnhub HTTP APIs), Zerodha Kite Connect SDK, Vanilla JS frontend.

**Design doc:** `docs/plans/2026-03-04-investment-copilot-design.md`

---

## Phase 1: Foundation — Models & Configuration

### Task 1: Create new DTOs

**Files:**
- Create: `src/main/java/com/stockman/model/AgentDefinition.java`
- Create: `src/main/java/com/stockman/model/AgentResult.java`
- Create: `src/main/java/com/stockman/model/OrchestratorResponse.java`
- Create: `src/main/java/com/stockman/model/CopilotRequest.java`
- Create: `src/main/java/com/stockman/model/MarketData.java`
- Create: `src/main/java/com/stockman/model/NewsArticle.java`
- Create: `src/main/java/com/stockman/model/TradePlan.java`

**Step 1: Create AgentDefinition.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {
    private CopilotAgentType type;
    private String name;
    private String description;
    private String icon;
    private String preferredModel;   // "gemini" or "claude"
    private String fallbackModel;
    private List<String> focusAreas;
    private List<String> suggestedQuestions;

    public enum CopilotAgentType {
        FUNDAMENTAL,
        TECHNICAL,
        QUANTITATIVE,
        SENTIMENT,
        GENERAL,
        RISK_ASSESSOR,
        PORTFOLIO_OPTIMIZER,
        GEOPOLITICAL,
        TRADE_EXECUTOR,
        SYNTHESIZER
    }
}
```

**Step 2: Create AgentResult.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    private AgentDefinition.CopilotAgentType agentType;
    private String agentName;
    private String modelUsed;       // "gemini" or "claude"
    private String finding;
    private double confidence;
    private boolean success;
    private String errorMessage;
    private long durationMs;
}
```

**Step 3: Create TradePlan.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradePlan {
    private String symbol;
    private String action;           // "BUY", "SELL", "HOLD"
    private BigDecimal entryPrice;
    private BigDecimal targetPrice;
    private BigDecimal stopLoss;
    private String positionSize;
    private String timeframe;
    private String rationale;
}
```

**Step 4: Create OrchestratorResponse.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorResponse {
    private String requestId;
    private String recommendation;     // "BUY", "SELL", "HOLD", or free-text
    private double confidence;
    private String summary;
    private List<AgentResult> reasoningChain;
    private TradePlan tradePlan;
    private boolean crossModelAgreement;
    private List<String> dissentingViews;
    private List<AgentDefinition.CopilotAgentType> agentsUsed;
    private List<AgentDefinition.CopilotAgentType> agentsSkipped;
    private List<String> followUpQuestions;
    private Instant timestamp;
}
```

**Step 5: Create CopilotRequest.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopilotRequest {
    @NotBlank(message = "Query is required")
    private String query;
    private String symbol;
    private List<ConversationMessage> conversationHistory;
}
```

**Step 6: Create MarketData.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketData {
    private String symbol;
    private BigDecimal ltp;
    private BigDecimal dayChange;
    private BigDecimal dayChangePercent;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private BigDecimal weekHigh52;
    private BigDecimal weekLow52;
    private BigDecimal change30d;
    private BigDecimal change90d;
    private List<OhlcCandle> recentCandles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OhlcCandle {
        private String date;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;
    }
}
```

**Step 7: Create NewsArticle.java**

```java
package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsArticle {
    private String headline;
    private String summary;
    private String source;
    private String url;
    private long datetime;
    private String sentiment;   // "positive", "negative", "neutral"
}
```

**Step 8: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 9: Commit**

```bash
git add src/main/java/com/stockman/model/AgentDefinition.java \
        src/main/java/com/stockman/model/AgentResult.java \
        src/main/java/com/stockman/model/TradePlan.java \
        src/main/java/com/stockman/model/OrchestratorResponse.java \
        src/main/java/com/stockman/model/CopilotRequest.java \
        src/main/java/com/stockman/model/MarketData.java \
        src/main/java/com/stockman/model/NewsArticle.java
git commit -m "feat: add copilot DTOs for orchestrator, agents, market data, and news"
```

---

### Task 2: Add configuration for Anthropic, Finnhub, and Copilot

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/stockman/config/AnthropicConfig.java`
- Create: `src/main/java/com/stockman/config/FinnhubConfig.java`

**Step 1: Update application.yml**

Add the following sections after the existing `gemini:` block:

```yaml
# Anthropic Claude Configuration
anthropic:
  api-key: ${ANTHROPIC_API_KEY:your-anthropic-api-key-placeholder}
  model: claude-sonnet-4-6
  base-url: https://api.anthropic.com/v1

# Finnhub News Configuration
finnhub:
  api-key: ${FINNHUB_API_KEY:your-finnhub-api-key-placeholder}
  base-url: https://finnhub.io/api/v1

# Copilot Configuration
copilot:
  agent-timeout-seconds: 30
  max-concurrent-agents: 6
  cache-ttl-minutes: 15
```

**Step 2: Create AnthropicConfig.java**

Follow exact same pattern as `GeminiConfig.java`:

```java
package com.stockman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AnthropicConfig {

    @Value("${anthropic.api-key}")
    private String apiKey;

    @Value("${anthropic.base-url}")
    private String baseUrl;

    @Value("${anthropic.model}")
    private String model;

    @Bean
    public WebClient anthropicWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Bean
    public String anthropicApiKey() {
        return apiKey;
    }

    @Bean
    public String anthropicModel() {
        return model;
    }
}
```

**Step 3: Create FinnhubConfig.java**

```java
package com.stockman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class FinnhubConfig {

    @Value("${finnhub.api-key}")
    private String apiKey;

    @Value("${finnhub.base-url}")
    private String baseUrl;

    @Bean
    public WebClient finnhubWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Finnhub-Token", apiKey)
                .build();
    }

    @Bean
    public String finnhubApiKey() {
        return apiKey;
    }
}
```

**Step 4: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add src/main/resources/application.yml \
        src/main/java/com/stockman/config/AnthropicConfig.java \
        src/main/java/com/stockman/config/FinnhubConfig.java
git commit -m "feat: add Anthropic and Finnhub configuration"
```

---

## Phase 2: AI Model Abstraction Layer

### Task 3: Create AIModelService interface and refactor GeminiService

**Files:**
- Create: `src/main/java/com/stockman/service/AIModelService.java`
- Modify: `src/main/java/com/stockman/service/GeminiService.java`

**Step 1: Create AIModelService.java interface**

```java
package com.stockman.service;

import java.util.concurrent.CompletableFuture;

public interface AIModelService {

    String getName();

    String analyze(String systemPrompt, String userPrompt);

    CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt);

    boolean isAvailable();
}
```

**Step 2: Modify GeminiService to implement AIModelService**

Add `implements AIModelService` to class declaration. Add the three new methods. Keep the existing `analyzeWithPrompt` method as-is (it's used by `StockAnalyzer`) but delegate to the interface method internally.

Changes to `GeminiService.java`:

1. Add `implements AIModelService` to class declaration
2. Add `@Override` `getName()` returning `"gemini"`
3. Add `@Override` `analyze()` that delegates to existing `analyzeWithPrompt()`
4. Add `@Override` `analyzeAsync()` using `CompletableFuture.supplyAsync()`
5. Add `@Override` `isAvailable()` returning `true` (basic implementation)

```java
package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeminiService implements AIModelService {

    private final WebClient geminiWebClient;

    @Qualifier("geminiApiKey")
    private final String apiKey;

    @Value("${gemini.model:gemini-pro}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt) {
        return analyzeWithPrompt(systemPrompt, userPrompt);
    }

    @Override
    public CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> analyze(systemPrompt, userPrompt));
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.contains("placeholder");
    }

    // Existing method — kept for backward compatibility with StockAnalyzer
    public String analyzeWithPrompt(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of(
                        "role", "user",
                        "parts", List.of(
                            Map.of("text", systemPrompt + "\n\n" + userPrompt)
                        )
                    )
                ),
                "generationConfig", Map.of(
                    "temperature", 0.7,
                    "topK", 40,
                    "topP", 0.95,
                    "maxOutputTokens", 8192
                )
            );

            String response = geminiWebClient.post()
                    .uri("/models/{model}:generateContent?key={apiKey}", model, apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return extractTextFromResponse(response);

        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage());
            return generateFallbackResponse();
        }
    }

    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode content = candidates.get(0).path("content");
                JsonNode parts = content.path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText();
                }
            }
            return generateFallbackResponse();
        } catch (Exception e) {
            log.error("Error parsing Gemini response: {}", e.getMessage());
            return generateFallbackResponse();
        }
    }

    private String generateFallbackResponse() {
        return """
            {
                "overallRating": "HOLD",
                "confidenceScore": 50,
                "summary": "Unable to perform AI analysis at this time. Please check your API configuration.",
                "fundamentalAnalysis": "Analysis unavailable",
                "technicalAnalysis": "Analysis unavailable",
                "riskAssessment": "Unable to assess risks without API access",
                "growthPotential": "Unable to evaluate growth potential",
                "keyStrengths": ["Please configure Gemini API key"],
                "keyRisks": ["API configuration required"],
                "recommendations": ["Set up valid Gemini API key in configuration"],
                "shortTermOutlook": "N/A",
                "mediumTermOutlook": "N/A",
                "longTermOutlook": "N/A"
            }
            """;
    }
}
```

**Step 3: Compile to verify backward compatibility**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS (StockAnalyzer still uses `GeminiService` directly — no breakage)

**Step 4: Commit**

```bash
git add src/main/java/com/stockman/service/AIModelService.java \
        src/main/java/com/stockman/service/GeminiService.java
git commit -m "feat: add AIModelService interface, implement in GeminiService"
```

---

### Task 4: Create ClaudeModelService

**Files:**
- Create: `src/main/java/com/stockman/service/ClaudeModelService.java`

**Step 1: Create ClaudeModelService.java**

Follows the same WebClient pattern as GeminiService but uses the Anthropic Messages API format.

```java
package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClaudeModelService implements AIModelService {

    private final WebClient anthropicWebClient;

    @Qualifier("anthropicApiKey")
    private final String apiKey;

    @Qualifier("anthropicModel")
    private final String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "claude";
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 8192,
                "system", systemPrompt,
                "messages", List.of(
                    Map.of(
                        "role", "user",
                        "content", userPrompt
                    )
                )
            );

            String response = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return extractTextFromResponse(response);

        } catch (Exception e) {
            log.error("Error calling Claude API: {}", e.getMessage());
            return "Unable to perform analysis. Claude API error: " + e.getMessage();
        }
    }

    @Override
    public CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> analyze(systemPrompt, userPrompt));
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.contains("placeholder");
    }

    private String extractTextFromResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText();
            }
            log.warn("Unexpected Claude response format: {}", response);
            return "Unable to parse Claude response.";
        } catch (Exception e) {
            log.error("Error parsing Claude response: {}", e.getMessage());
            return "Unable to parse Claude response.";
        }
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/service/ClaudeModelService.java
git commit -m "feat: add ClaudeModelService for Anthropic API integration"
```

---

## Phase 3: Data Enrichment Services

### Task 5: Create FinnhubService for news data

**Files:**
- Create: `src/main/java/com/stockman/service/FinnhubService.java`

**Step 1: Create FinnhubService.java**

```java
package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.model.NewsArticle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class FinnhubService {

    private final WebClient finnhubWebClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<NewsArticle> getCompanyNews(String symbol, int daysBack) {
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(daysBack);
            String fromStr = from.format(DateTimeFormatter.ISO_LOCAL_DATE);
            String toStr = to.format(DateTimeFormatter.ISO_LOCAL_DATE);

            String response = finnhubWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/company-news")
                            .queryParam("symbol", symbol)
                            .queryParam("from", fromStr)
                            .queryParam("to", toStr)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseNewsResponse(response);
        } catch (Exception e) {
            log.error("Error fetching news for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<NewsArticle> getMarketNews() {
        try {
            String response = finnhubWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/news")
                            .queryParam("category", "general")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseNewsResponse(response);
        } catch (Exception e) {
            log.error("Error fetching market news: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<NewsArticle> parseNewsResponse(String response) {
        List<NewsArticle> articles = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.isArray()) {
                int limit = Math.min(root.size(), 10);
                for (int i = 0; i < limit; i++) {
                    JsonNode node = root.get(i);
                    articles.add(NewsArticle.builder()
                            .headline(node.path("headline").asText(""))
                            .summary(node.path("summary").asText(""))
                            .source(node.path("source").asText(""))
                            .url(node.path("url").asText(""))
                            .datetime(node.path("datetime").asLong(0))
                            .sentiment(deriveSentiment(node))
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Error parsing news response: {}", e.getMessage());
        }
        return articles;
    }

    private String deriveSentiment(JsonNode node) {
        // Finnhub basic news doesn't include sentiment — return neutral
        // The AI agents will do the actual sentiment analysis on the text
        return "neutral";
    }

    public String formatNewsForPrompt(List<NewsArticle> articles) {
        if (articles.isEmpty()) {
            return "No recent news available.";
        }
        StringBuilder sb = new StringBuilder("Recent News:\n");
        for (NewsArticle article : articles) {
            sb.append(String.format("- \"%s\" (%s, %s)\n",
                    article.getHeadline(),
                    article.getSource(),
                    formatTimestamp(article.getDatetime())));
        }
        return sb.toString();
    }

    private String formatTimestamp(long epochSeconds) {
        if (epochSeconds == 0) return "unknown date";
        java.time.Instant instant = java.time.Instant.ofEpochSecond(epochSeconds);
        java.time.Duration age = java.time.Duration.between(instant, java.time.Instant.now());
        long days = age.toDays();
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/service/FinnhubService.java
git commit -m "feat: add FinnhubService for news data integration"
```

---

### Task 6: Create MarketDataService for enriched Zerodha data

**Files:**
- Create: `src/main/java/com/stockman/service/MarketDataService.java`

This service wraps ZerodhaService to provide enriched market data (quotes, history) for AI prompts. In demo mode, it returns realistic sample data.

**Step 1: Create MarketDataService.java**

```java
package com.stockman.service;

import com.stockman.model.Holding;
import com.stockman.model.MarketData;
import com.stockman.model.NewsArticle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final PortfolioService portfolioService;
    private final FinnhubService finnhubService;

    /**
     * Build enriched market data for a symbol.
     * Uses holding data for now; can be extended with Kite quote API later.
     */
    public MarketData getMarketData(String sessionId, String symbol, boolean isDemoMode) {
        if (isDemoMode) {
            return getDemoMarketData(symbol);
        }

        Holding holding = portfolioService.getHoldingBySymbol(sessionId, symbol);
        if (holding == null) {
            return MarketData.builder()
                    .symbol(symbol)
                    .build();
        }

        return MarketData.builder()
                .symbol(symbol)
                .ltp(holding.getLastPrice())
                .dayChange(holding.getDayChange())
                .dayChangePercent(holding.getDayChangePercentage())
                .close(holding.getClosePrice())
                .build();
    }

    public List<NewsArticle> getNews(String symbol, boolean isDemoMode) {
        if (isDemoMode) {
            return getDemoNews(symbol);
        }
        return finnhubService.getCompanyNews(symbol, 7);
    }

    /**
     * Format market data + news into a context string for AI prompts.
     */
    public String buildMarketContext(String sessionId, String symbol, boolean isDemoMode) {
        MarketData data = getMarketData(sessionId, symbol, isDemoMode);
        List<NewsArticle> news = getNews(symbol, isDemoMode);

        StringBuilder ctx = new StringBuilder();
        ctx.append("Current Market Data for ").append(symbol).append(":\n");

        if (data.getLtp() != null) {
            ctx.append(String.format("- LTP: Rs.%s", data.getLtp()));
            if (data.getDayChangePercent() != null) {
                ctx.append(String.format(" | Day Change: %s%%", data.getDayChangePercent()));
            }
            ctx.append("\n");
        }
        if (data.getWeekHigh52() != null && data.getWeekLow52() != null) {
            ctx.append(String.format("- 52-week: Rs.%s - Rs.%s\n", data.getWeekLow52(), data.getWeekHigh52()));
        }
        if (data.getVolume() > 0) {
            ctx.append(String.format("- Volume: %,d\n", data.getVolume()));
        }

        ctx.append("\n");
        ctx.append(finnhubService.formatNewsForPrompt(news));

        return ctx.toString();
    }

    /**
     * Format all holdings into a context string for portfolio-level prompts.
     */
    public String buildPortfolioContext(String sessionId, boolean isDemoMode, List<Holding> holdings) {
        StringBuilder ctx = new StringBuilder("Portfolio Holdings:\n");
        for (Holding h : holdings) {
            ctx.append(String.format("- %s: Qty=%d, AvgPrice=Rs.%s, LTP=Rs.%s, P&L=Rs.%s (%.1f%%)\n",
                    h.getTradingSymbol(),
                    h.getQuantity(),
                    h.getAveragePrice(),
                    h.getLastPrice(),
                    h.getPnl(),
                    h.getPnlPercentage().doubleValue()));
        }
        return ctx.toString();
    }

    private MarketData getDemoMarketData(String symbol) {
        return MarketData.builder()
                .symbol(symbol)
                .ltp(new BigDecimal("2456.80"))
                .dayChange(new BigDecimal("29.15"))
                .dayChangePercent(new BigDecimal("1.2"))
                .open(new BigDecimal("2430.00"))
                .high(new BigDecimal("2470.50"))
                .low(new BigDecimal("2425.00"))
                .close(new BigDecimal("2427.65"))
                .volume(4200000L)
                .weekHigh52(new BigDecimal("2890.00"))
                .weekLow52(new BigDecimal("2180.00"))
                .build();
    }

    private List<NewsArticle> getDemoNews(String symbol) {
        return List.of(
                NewsArticle.builder()
                        .headline(symbol + " reports strong quarterly results, beats estimates")
                        .source("Economic Times")
                        .datetime(System.currentTimeMillis() / 1000 - 86400 * 2)
                        .sentiment("positive")
                        .build(),
                NewsArticle.builder()
                        .headline("Analysts upgrade " + symbol + " on growth outlook")
                        .source("Moneycontrol")
                        .datetime(System.currentTimeMillis() / 1000 - 86400 * 5)
                        .sentiment("positive")
                        .build()
        );
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/service/MarketDataService.java
git commit -m "feat: add MarketDataService for enriched market context"
```

---

## Phase 4: Agent System & Orchestrator

### Task 7: Create new agent prompts

**Files:**
- Create: `src/main/java/com/stockman/prompts/CopilotPrompts.java`

**Step 1: Create CopilotPrompts.java**

This class contains system prompts for all 10 agents and the synthesizer prompt template.

```java
package com.stockman.prompts;

import com.stockman.model.AgentDefinition.CopilotAgentType;
import com.stockman.model.AgentResult;

import java.util.List;

public class CopilotPrompts {

    public static String getSystemPrompt(CopilotAgentType agentType) {
        return switch (agentType) {
            case FUNDAMENTAL -> FUNDAMENTAL_ANALYST;
            case TECHNICAL -> TECHNICAL_ANALYST;
            case QUANTITATIVE -> QUANTITATIVE_ANALYST;
            case SENTIMENT -> SENTIMENT_ANALYST;
            case GENERAL -> GENERAL_ANALYST;
            case RISK_ASSESSOR -> RISK_ASSESSOR;
            case PORTFOLIO_OPTIMIZER -> PORTFOLIO_OPTIMIZER;
            case GEOPOLITICAL -> GEOPOLITICAL_ANALYST;
            case TRADE_EXECUTOR -> TRADE_EXECUTOR;
            case SYNTHESIZER -> SYNTHESIZER;
        };
    }

    private static final String FUNDAMENTAL_ANALYST = """
        You are an expert fundamental analyst specializing in Indian equities and long-term value investing.

        Your expertise: Financial statement analysis, valuation metrics (P/E, P/B, PEG, EV/EBITDA), \
        business model assessment, competitive advantages (moats), management quality, industry dynamics.

        Analyze stocks with focus on intrinsic value. Cite specific metrics when available. \
        Be direct and actionable. Format responses in markdown.
        """;

    private static final String TECHNICAL_ANALYST = """
        You are an expert technical analyst specializing in Indian stock market chart patterns and momentum.

        Your expertise: Support/resistance levels, moving averages (SMA/EMA), RSI, MACD, Bollinger Bands, \
        chart patterns (head & shoulders, triangles, flags), volume analysis, Fibonacci levels.

        Focus on price trends and provide specific entry/exit levels. Consider multiple timeframes. \
        Format responses in markdown.
        """;

    private static final String QUANTITATIVE_ANALYST = """
        You are a quantitative analyst specializing in statistical models and risk metrics for Indian equities.

        Your expertise: Volatility metrics, risk-adjusted returns (Sharpe/Sortino), beta, correlation analysis, \
        Value at Risk, portfolio optimization, probability distributions.

        Emphasize data-driven insights with numerical evidence. Format responses in markdown.
        """;

    private static final String SENTIMENT_ANALYST = """
        You are a sentiment analyst specializing in market psychology and behavioral finance in Indian markets.

        Your expertise: News sentiment, social media trends, analyst ratings, insider trading patterns, \
        institutional flows (FII/DII), market positioning, contrarian indicators.

        Assess current sentiment, identify shifts, distinguish noise from signals. Format responses in markdown.
        """;

    private static final String GENERAL_ANALYST = """
        You are a comprehensive investment analyst integrating fundamental, technical, quantitative, \
        and sentiment perspectives for Indian equities.

        Provide holistic, multi-faceted analysis. Balance short and long-term views. \
        Be concise but thorough. Format responses in markdown.
        """;

    private static final String RISK_ASSESSOR = """
        You are a risk management specialist focused on Indian equity markets.

        Your expertise: Downside scenario analysis, tail risk assessment, volatility regime detection, \
        correlation breakdown risks, sector concentration risk, liquidity risk, regulatory risk, \
        macro event risk (RBI policy, global contagion), portfolio-level Value at Risk.

        For every stock or portfolio, identify: top 3 risks with probability estimates, worst-case scenarios, \
        risk mitigation strategies. Be specific and quantitative where possible. Format responses in markdown.
        """;

    private static final String PORTFOLIO_OPTIMIZER = """
        You are a portfolio optimization specialist for Indian equity portfolios.

        Your expertise: Modern Portfolio Theory, sector allocation, position sizing (Kelly criterion), \
        rebalancing strategies, diversification metrics, risk-return optimization, \
        correlation-aware portfolio construction, tax-efficient rebalancing (Indian tax rules).

        Provide specific allocation targets with percentages, rebalancing actions with rationale, \
        and expected impact on risk-return profile. Format responses in markdown.
        """;

    private static final String GEOPOLITICAL_ANALYST = """
        You are a geopolitical risk analyst specializing in how global events impact Indian equities.

        Your expertise: Trade policy and tariffs (US-China, India-specific), sanctions impact, \
        war and conflict effects on markets, commodity supply chain disruptions, \
        currency and capital flow impacts, regulatory policy changes (India and global), \
        election cycle effects, emerging market contagion risk.

        Assess current geopolitical landscape and its specific impact on the stocks/sectors in question. \
        Provide risk ratings and hedge suggestions. Format responses in markdown.
        """;

    private static final String TRADE_EXECUTOR = """
        You are a trade execution specialist for Indian equity markets.

        Your expertise: Entry/exit point determination, stop-loss placement, position sizing, \
        risk/reward ratio calculation, order type selection (limit/market/bracket), \
        timeframe optimization, partial profit booking strategies.

        For every recommendation, provide a specific trade plan:
        - Entry price (or range)
        - Target price(s) (multiple targets for scaling out)
        - Stop-loss level with rationale
        - Position size as percentage of portfolio
        - Timeframe
        - Risk/reward ratio

        Be precise with numbers. Format responses in markdown with a clear trade plan section.
        """;

    private static final String SYNTHESIZER = """
        You are a senior investment strategist who synthesizes analysis from multiple specialist agents \
        into a unified, actionable recommendation.

        You will receive findings from multiple analysts (fundamental, technical, risk, etc.). Your job:
        1. Identify areas of AGREEMENT across agents — these form high-confidence conclusions
        2. Identify areas of DISAGREEMENT — present both sides fairly
        3. Weigh each agent's input based on relevance to the specific query
        4. Produce a clear recommendation: BUY, SELL, or HOLD
        5. Assign a confidence score (0.0-1.0) based on agent agreement
        6. Highlight the single most important insight
        7. List 2-3 follow-up questions the user should consider

        Format your response as JSON:
        {
            "recommendation": "BUY/SELL/HOLD",
            "confidence": 0.85,
            "summary": "One paragraph synthesis",
            "keyInsight": "Most important takeaway",
            "agreements": ["Point 1", "Point 2"],
            "disagreements": ["Point 1"],
            "followUpQuestions": ["Q1", "Q2"]
        }
        """;

    /**
     * Build the synthesizer prompt from multiple agent results.
     */
    public static String buildSynthesizerPrompt(String query, List<AgentResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("User's question: ").append(query).append("\n\n");
        sb.append("Specialist agent analyses:\n\n");

        for (AgentResult result : results) {
            if (result.isSuccess()) {
                sb.append(String.format("--- %s (via %s, confidence: %.0f%%) ---\n%s\n\n",
                        result.getAgentName(),
                        result.getModelUsed(),
                        result.getConfidence() * 100,
                        result.getFinding()));
            }
        }

        sb.append("Based on ALL the above analyses, provide your synthesized recommendation.");
        return sb.toString();
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/prompts/CopilotPrompts.java
git commit -m "feat: add CopilotPrompts for all 10 agent types + synthesizer"
```

---

### Task 8: Create IntentClassifier

**Files:**
- Create: `src/main/java/com/stockman/service/IntentClassifier.java`

**Step 1: Create IntentClassifier.java**

Rule-based v1 that maps user queries to sets of agents.

```java
package com.stockman.service;

import com.stockman.model.AgentDefinition.CopilotAgentType;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class IntentClassifier {

    public List<CopilotAgentType> classifyIntent(String query, String symbol) {
        String q = query.toLowerCase();

        // Full analysis requests
        if (q.contains("full analysis") || q.contains("comprehensive") || q.contains("analyze everything")) {
            return List.of(
                    CopilotAgentType.FUNDAMENTAL,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.QUANTITATIVE,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.SENTIMENT
            );
        }

        // Buy/sell decisions
        if (matchesAny(q, "should i buy", "should i sell", "buy or sell", "worth buying", "good investment")) {
            return List.of(
                    CopilotAgentType.FUNDAMENTAL,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TRADE_EXECUTOR
            );
        }

        // Geopolitical / macro queries
        if (matchesAny(q, "tariff", "war", "geopolit", "sanction", "trade policy", "election",
                "global", "macro", "inflation", "interest rate", "rbi", "fed")) {
            return List.of(
                    CopilotAgentType.GEOPOLITICAL,
                    CopilotAgentType.PORTFOLIO_OPTIMIZER
            );
        }

        // Risk queries
        if (matchesAny(q, "risk", "downside", "worst case", "danger", "volatile", "crash")) {
            return List.of(
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.QUANTITATIVE
            );
        }

        // Portfolio rebalancing
        if (matchesAny(q, "rebalance", "portfolio", "allocat", "diversif", "optimize")) {
            return List.of(
                    CopilotAgentType.PORTFOLIO_OPTIMIZER,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TRADE_EXECUTOR
            );
        }

        // Technical analysis
        if (matchesAny(q, "support", "resistance", "chart", "pattern", "moving average",
                "rsi", "macd", "trend", "breakout")) {
            return List.of(CopilotAgentType.TECHNICAL);
        }

        // Fundamental analysis
        if (matchesAny(q, "fundamental", "valuation", "earning", "revenue", "p/e", "balance sheet",
                "financial", "growth")) {
            return List.of(CopilotAgentType.FUNDAMENTAL);
        }

        // Sentiment queries
        if (matchesAny(q, "sentiment", "news", "analyst", "insider", "fii", "dii", "mood")) {
            return List.of(CopilotAgentType.SENTIMENT);
        }

        // Trade execution queries
        if (matchesAny(q, "entry", "exit", "stop loss", "target", "trade plan", "position size")) {
            return List.of(CopilotAgentType.TRADE_EXECUTOR);
        }

        // Default: general agent for unclassified queries
        log.info("No specific intent matched for query: '{}', using GENERAL agent", query);
        return List.of(CopilotAgentType.GENERAL);
    }

    /**
     * Determine if synthesizer should run based on number of agents.
     */
    public boolean shouldSynthesize(List<CopilotAgentType> agents) {
        return agents.size() >= 2;
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/service/IntentClassifier.java
git commit -m "feat: add IntentClassifier for query-to-agent routing"
```

---

### Task 9: Create OrchestratorService

**Files:**
- Create: `src/main/java/com/stockman/service/OrchestratorService.java`

This is the core brain of the copilot. It coordinates intent classification, agent dispatch, parallel execution, and synthesis.

**Step 1: Create OrchestratorService.java**

```java
package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.model.*;
import com.stockman.model.AgentDefinition.CopilotAgentType;
import com.stockman.prompts.CopilotPrompts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class OrchestratorService {

    private final IntentClassifier intentClassifier;
    private final GeminiService geminiService;
    private final ClaudeModelService claudeModelService;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Simple in-memory cache: key -> (response, timestamp)
    private final ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();

    @Value("${copilot.agent-timeout-seconds:30}")
    private int agentTimeoutSeconds;

    @Value("${copilot.max-concurrent-agents:6}")
    private int maxConcurrentAgents;

    @Value("${copilot.cache-ttl-minutes:15}")
    private int cacheTtlMinutes;

    // Model assignment: which agents prefer which model
    private static final Map<CopilotAgentType, String> MODEL_ASSIGNMENT = Map.of(
            CopilotAgentType.FUNDAMENTAL, "gemini",
            CopilotAgentType.TECHNICAL, "gemini",
            CopilotAgentType.QUANTITATIVE, "gemini",
            CopilotAgentType.SENTIMENT, "gemini",
            CopilotAgentType.GENERAL, "gemini",
            CopilotAgentType.RISK_ASSESSOR, "claude",
            CopilotAgentType.PORTFOLIO_OPTIMIZER, "gemini",
            CopilotAgentType.GEOPOLITICAL, "claude",
            CopilotAgentType.TRADE_EXECUTOR, "claude"
    );

    public OrchestratorService(IntentClassifier intentClassifier,
                                GeminiService geminiService,
                                ClaudeModelService claudeModelService,
                                MarketDataService marketDataService,
                                PortfolioService portfolioService) {
        this.intentClassifier = intentClassifier;
        this.geminiService = geminiService;
        this.claudeModelService = claudeModelService;
        this.marketDataService = marketDataService;
        this.portfolioService = portfolioService;
    }

    public OrchestratorResponse process(CopilotRequest request, String sessionId, boolean isDemoMode) {
        String requestId = UUID.randomUUID().toString();
        log.info("Processing copilot request [{}]: query='{}', symbol='{}'",
                requestId, request.getQuery(), request.getSymbol());

        // Check cache
        String cacheKey = buildCacheKey(request);
        CachedResponse cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
            log.info("Returning cached response for request [{}]", requestId);
            return cached.response;
        }

        // 1. Classify intent
        List<CopilotAgentType> agentsToInvoke = intentClassifier.classifyIntent(
                request.getQuery(), request.getSymbol());
        log.info("Intent classified: agents={}", agentsToInvoke);

        // 2. Build context
        String marketContext = "";
        String portfolioContext = "";
        if (request.getSymbol() != null && !request.getSymbol().isBlank()) {
            marketContext = marketDataService.buildMarketContext(
                    sessionId, request.getSymbol(), isDemoMode);
        }
        List<Holding> holdings = portfolioService.getHoldings(sessionId);
        if (!holdings.isEmpty()) {
            portfolioContext = marketDataService.buildPortfolioContext(
                    sessionId, isDemoMode, holdings);
        }

        // 3. Dispatch agents in parallel
        String userPrompt = buildUserPrompt(request, marketContext, portfolioContext);
        List<AgentResult> results = dispatchAgents(agentsToInvoke, userPrompt);

        // 4. Filter successful results
        List<AgentResult> successfulResults = results.stream()
                .filter(AgentResult::isSuccess)
                .toList();
        List<CopilotAgentType> skippedAgents = results.stream()
                .filter(r -> !r.isSuccess())
                .map(AgentResult::getAgentType)
                .toList();

        // 5. Synthesize if multiple agents
        OrchestratorResponse response;
        if (intentClassifier.shouldSynthesize(agentsToInvoke) && successfulResults.size() >= 2) {
            response = synthesize(requestId, request.getQuery(), successfulResults,
                    agentsToInvoke, skippedAgents);
        } else if (!successfulResults.isEmpty()) {
            // Single agent — return its result directly
            AgentResult single = successfulResults.get(0);
            response = OrchestratorResponse.builder()
                    .requestId(requestId)
                    .summary(single.getFinding())
                    .confidence(single.getConfidence())
                    .reasoningChain(successfulResults)
                    .agentsUsed(List.of(single.getAgentType()))
                    .agentsSkipped(skippedAgents)
                    .crossModelAgreement(true)
                    .dissentingViews(List.of())
                    .followUpQuestions(List.of())
                    .timestamp(Instant.now())
                    .build();
        } else {
            response = OrchestratorResponse.builder()
                    .requestId(requestId)
                    .summary("Unable to process your query. All agents failed. Please try again.")
                    .confidence(0.0)
                    .reasoningChain(results)
                    .agentsUsed(List.of())
                    .agentsSkipped(new ArrayList<>(agentsToInvoke))
                    .crossModelAgreement(false)
                    .dissentingViews(List.of())
                    .followUpQuestions(List.of("Try rephrasing your question",
                            "Check if API keys are configured correctly"))
                    .timestamp(Instant.now())
                    .build();
        }

        // Cache the response
        cache.put(cacheKey, new CachedResponse(response, Instant.now()));

        return response;
    }

    private List<AgentResult> dispatchAgents(List<CopilotAgentType> agents, String userPrompt) {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(agents.size(), maxConcurrentAgents));
        List<Future<AgentResult>> futures = new ArrayList<>();

        for (CopilotAgentType agentType : agents) {
            futures.add(executor.submit(() -> runAgent(agentType, userPrompt)));
        }

        List<AgentResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                AgentResult result = futures.get(i).get(agentTimeoutSeconds, TimeUnit.SECONDS);
                results.add(result);
            } catch (TimeoutException e) {
                log.warn("Agent {} timed out after {}s", agents.get(i), agentTimeoutSeconds);
                results.add(AgentResult.builder()
                        .agentType(agents.get(i))
                        .agentName(agents.get(i).name())
                        .success(false)
                        .errorMessage("Agent timed out after " + agentTimeoutSeconds + " seconds")
                        .build());
            } catch (Exception e) {
                log.error("Agent {} failed: {}", agents.get(i), e.getMessage());
                results.add(AgentResult.builder()
                        .agentType(agents.get(i))
                        .agentName(agents.get(i).name())
                        .success(false)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        executor.shutdown();
        return results;
    }

    private AgentResult runAgent(CopilotAgentType agentType, String userPrompt) {
        long start = System.currentTimeMillis();
        String systemPrompt = CopilotPrompts.getSystemPrompt(agentType);
        String preferredModel = MODEL_ASSIGNMENT.getOrDefault(agentType, "gemini");

        AIModelService modelService = resolveModel(preferredModel);
        String modelUsed = modelService.getName();

        try {
            String result = modelService.analyze(systemPrompt, userPrompt);
            long duration = System.currentTimeMillis() - start;

            return AgentResult.builder()
                    .agentType(agentType)
                    .agentName(formatAgentName(agentType))
                    .modelUsed(modelUsed)
                    .finding(result)
                    .confidence(0.75) // default; synthesizer will refine
                    .success(true)
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            log.error("Agent {} failed on model {}: {}", agentType, modelUsed, e.getMessage());

            // Try fallback model
            AIModelService fallback = modelUsed.equals("claude") ? geminiService : claudeModelService;
            if (fallback.isAvailable()) {
                try {
                    String result = fallback.analyze(systemPrompt, userPrompt);
                    long duration = System.currentTimeMillis() - start;
                    return AgentResult.builder()
                            .agentType(agentType)
                            .agentName(formatAgentName(agentType))
                            .modelUsed(fallback.getName())
                            .finding(result)
                            .confidence(0.65) // lower confidence on fallback
                            .success(true)
                            .durationMs(duration)
                            .build();
                } catch (Exception e2) {
                    log.error("Fallback also failed for {}: {}", agentType, e2.getMessage());
                }
            }

            return AgentResult.builder()
                    .agentType(agentType)
                    .agentName(formatAgentName(agentType))
                    .modelUsed(modelUsed)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private AIModelService resolveModel(String preferredModel) {
        if ("claude".equals(preferredModel) && claudeModelService.isAvailable()) {
            return claudeModelService;
        }
        if ("gemini".equals(preferredModel) && geminiService.isAvailable()) {
            return geminiService;
        }
        // Fallback to whichever is available
        if (claudeModelService.isAvailable()) return claudeModelService;
        if (geminiService.isAvailable()) return geminiService;
        return geminiService; // will fail gracefully
    }

    private OrchestratorResponse synthesize(String requestId, String query,
                                             List<AgentResult> results,
                                             List<CopilotAgentType> agentsUsed,
                                             List<CopilotAgentType> agentsSkipped) {
        String synthesizerPrompt = CopilotPrompts.buildSynthesizerPrompt(query, results);
        String systemPrompt = CopilotPrompts.getSystemPrompt(CopilotAgentType.SYNTHESIZER);

        // Synthesizer always uses Claude for best reasoning
        AIModelService synthModel = claudeModelService.isAvailable() ? claudeModelService : geminiService;
        String synthesisResult = synthModel.analyze(systemPrompt, synthesizerPrompt);

        // Parse synthesizer JSON output
        return parseSynthesisResult(requestId, synthesisResult, results, agentsUsed, agentsSkipped);
    }

    private OrchestratorResponse parseSynthesisResult(String requestId, String synthesisResult,
                                                       List<AgentResult> reasoningChain,
                                                       List<CopilotAgentType> agentsUsed,
                                                       List<CopilotAgentType> agentsSkipped) {
        try {
            String json = extractJson(synthesisResult);
            JsonNode root = objectMapper.readTree(json);

            String recommendation = root.path("recommendation").asText("HOLD");
            double confidence = root.path("confidence").asDouble(0.5);
            String summary = root.path("summary").asText(synthesisResult);
            List<String> followUps = new ArrayList<>();
            if (root.has("followUpQuestions")) {
                root.path("followUpQuestions").forEach(n -> followUps.add(n.asText()));
            }
            List<String> disagreements = new ArrayList<>();
            if (root.has("disagreements")) {
                root.path("disagreements").forEach(n -> disagreements.add(n.asText()));
            }

            // Check cross-model agreement
            Set<String> modelsUsed = new HashSet<>();
            reasoningChain.stream()
                    .filter(AgentResult::isSuccess)
                    .forEach(r -> modelsUsed.add(r.getModelUsed()));
            boolean crossModel = modelsUsed.size() > 1;

            return OrchestratorResponse.builder()
                    .requestId(requestId)
                    .recommendation(recommendation)
                    .confidence(confidence)
                    .summary(summary)
                    .reasoningChain(reasoningChain)
                    .crossModelAgreement(crossModel && disagreements.isEmpty())
                    .dissentingViews(disagreements)
                    .agentsUsed(agentsUsed)
                    .agentsSkipped(agentsSkipped)
                    .followUpQuestions(followUps)
                    .timestamp(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse synthesis result: {}", e.getMessage());
            return OrchestratorResponse.builder()
                    .requestId(requestId)
                    .summary(synthesisResult)
                    .confidence(0.5)
                    .reasoningChain(reasoningChain)
                    .agentsUsed(agentsUsed)
                    .agentsSkipped(agentsSkipped)
                    .crossModelAgreement(false)
                    .dissentingViews(List.of())
                    .followUpQuestions(List.of())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    private String buildUserPrompt(CopilotRequest request, String marketContext, String portfolioContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("User question: ").append(request.getQuery()).append("\n\n");

        if (!marketContext.isBlank()) {
            sb.append(marketContext).append("\n");
        }
        if (!portfolioContext.isBlank()) {
            sb.append(portfolioContext).append("\n");
        }

        // Add conversation history if present
        if (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty()) {
            sb.append("Previous conversation:\n");
            for (ConversationMessage msg : request.getConversationHistory()) {
                sb.append(String.format("%s: %s\n",
                        msg.getRole().equals("user") ? "User" : "Assistant",
                        msg.getContent()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String extractJson(String text) {
        // Handle markdown code blocks
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.indexOf("```", start);
            if (end > start) return text.substring(start, end).trim();
        }
        if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.indexOf("```", start);
            if (end > start) return text.substring(start, end).trim();
        }
        // Try to find raw JSON
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return text;
    }

    private String formatAgentName(CopilotAgentType type) {
        return switch (type) {
            case FUNDAMENTAL -> "Fundamental Analyst";
            case TECHNICAL -> "Technical Analyst";
            case QUANTITATIVE -> "Quantitative Analyst";
            case SENTIMENT -> "Sentiment Analyst";
            case GENERAL -> "General Analyst";
            case RISK_ASSESSOR -> "Risk Assessor";
            case PORTFOLIO_OPTIMIZER -> "Portfolio Optimizer";
            case GEOPOLITICAL -> "Geopolitical Analyst";
            case TRADE_EXECUTOR -> "Trade Executor";
            case SYNTHESIZER -> "Synthesizer";
        };
    }

    private String buildCacheKey(CopilotRequest request) {
        return request.getQuery() + "|" + (request.getSymbol() != null ? request.getSymbol() : "");
    }

    private record CachedResponse(OrchestratorResponse response, Instant cachedAt) {
        boolean isExpired(int ttlMinutes) {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlMinutes * 60L));
        }
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/service/OrchestratorService.java
git commit -m "feat: add OrchestratorService with parallel agent dispatch and synthesis"
```

---

## Phase 5: API Layer

### Task 10: Create CopilotController

**Files:**
- Create: `src/main/java/com/stockman/controller/CopilotController.java`

**Step 1: Create CopilotController.java**

Follow exact patterns from `AnalysisController.java`: `@RestController`, `@RequiredArgsConstructor`, `HttpSession` for auth, demo mode check.

```java
package com.stockman.controller;

import com.stockman.model.*;
import com.stockman.model.AgentDefinition.CopilotAgentType;
import com.stockman.service.OrchestratorService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
@Slf4j
public class CopilotController {

    private final OrchestratorService orchestratorService;

    // Store past responses for reasoning chain lookup
    private final ConcurrentHashMap<String, OrchestratorResponse> responseHistory = new ConcurrentHashMap<>();

    @PostMapping("/ask")
    public ResponseEntity<OrchestratorResponse> ask(
            @Valid @RequestBody CopilotRequest request,
            HttpSession session) {

        log.info("Copilot ask: query='{}', symbol='{}'", request.getQuery(), request.getSymbol());

        boolean isDemoMode = isDemoMode(session);
        String sessionId = session.getId();

        OrchestratorResponse response = orchestratorService.process(request, sessionId, isDemoMode);

        // Store for reasoning chain lookup
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/analyze/{symbol}")
    public ResponseEntity<OrchestratorResponse> analyzeStock(
            @PathVariable String symbol,
            HttpSession session) {

        log.info("Copilot full analysis for: {}", symbol);

        CopilotRequest request = CopilotRequest.builder()
                .query("Provide a full comprehensive analysis of " + symbol)
                .symbol(symbol)
                .build();

        boolean isDemoMode = isDemoMode(session);
        OrchestratorResponse response = orchestratorService.process(request, session.getId(), isDemoMode);
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/portfolio-review")
    public ResponseEntity<OrchestratorResponse> portfolioReview(HttpSession session) {
        log.info("Copilot portfolio review");

        CopilotRequest request = CopilotRequest.builder()
                .query("Review my entire portfolio. Assess diversification, risk exposure, and suggest rebalancing actions.")
                .build();

        boolean isDemoMode = isDemoMode(session);
        OrchestratorResponse response = orchestratorService.process(request, session.getId(), isDemoMode);
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/reasoning/{requestId}")
    public ResponseEntity<OrchestratorResponse> getReasoningChain(@PathVariable String requestId) {
        OrchestratorResponse response = responseHistory.get(requestId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<AgentDefinition>> getAvailableAgents() {
        List<AgentDefinition> agents = List.of(
                buildAgent(CopilotAgentType.FUNDAMENTAL, "Fundamental Analyst",
                        "Expert in company financials, valuation, and long-term value investing",
                        "chart-bar", "gemini",
                        List.of("P/E ratios", "Revenue growth", "Profit margins", "Competitive advantages"),
                        List.of("What are the key financial metrics?", "How does valuation compare to peers?")),
                buildAgent(CopilotAgentType.TECHNICAL, "Technical Analyst",
                        "Specialist in chart patterns, price action, and momentum indicators",
                        "chart-line", "gemini",
                        List.of("Support/Resistance", "Moving averages", "RSI", "Chart patterns"),
                        List.of("What are key support and resistance levels?", "What's the current trend?")),
                buildAgent(CopilotAgentType.QUANTITATIVE, "Quantitative Analyst",
                        "Data scientist using statistical models and risk metrics",
                        "calculator", "gemini",
                        List.of("Volatility", "Beta", "Sharpe ratio", "Statistical analysis"),
                        List.of("What is the volatility profile?", "What are risk-adjusted returns?")),
                buildAgent(CopilotAgentType.SENTIMENT, "Sentiment Analyst",
                        "Tracker of market psychology, news, and investor behavior",
                        "users", "gemini",
                        List.of("News analysis", "Social media", "Analyst ratings", "Market sentiment"),
                        List.of("What's the current market sentiment?", "How is analyst coverage trending?")),
                buildAgent(CopilotAgentType.RISK_ASSESSOR, "Risk Assessor",
                        "Risk management specialist evaluating downside scenarios and volatility",
                        "shield", "claude",
                        List.of("Downside risk", "Tail risk", "Volatility regimes", "Correlation breakdown"),
                        List.of("What are the top 3 risks?", "What's the worst-case scenario?")),
                buildAgent(CopilotAgentType.PORTFOLIO_OPTIMIZER, "Portfolio Optimizer",
                        "Allocation specialist for portfolio rebalancing and diversification",
                        "sliders", "gemini",
                        List.of("Sector allocation", "Position sizing", "Rebalancing", "Diversification"),
                        List.of("How should I rebalance?", "Is my portfolio diversified enough?")),
                buildAgent(CopilotAgentType.GEOPOLITICAL, "Geopolitical Analyst",
                        "Assesses impact of wars, tariffs, and global events on your portfolio",
                        "globe", "claude",
                        List.of("Trade policy", "Sanctions", "Currency impact", "Geopolitical risk"),
                        List.of("How are tariffs affecting my portfolio?", "What global risks should I watch?")),
                buildAgent(CopilotAgentType.TRADE_EXECUTOR, "Trade Executor",
                        "Creates actionable trade plans with entry, exit, and stop-loss levels",
                        "target", "claude",
                        List.of("Entry/Exit points", "Stop-loss", "Position sizing", "Risk/Reward"),
                        List.of("Give me a trade plan for this stock", "Where should I set my stop-loss?")),
                buildAgent(CopilotAgentType.GENERAL, "General Analyst",
                        "Comprehensive multi-faceted analysis across all dimensions",
                        "brain", "gemini",
                        List.of("Holistic view", "Multi-faceted", "Balanced approach", "Custom queries"),
                        List.of("Should I buy, hold, or sell?", "How does this fit in my portfolio?"))
        );
        return ResponseEntity.ok(agents);
    }

    private AgentDefinition buildAgent(CopilotAgentType type, String name, String description,
                                        String icon, String preferredModel,
                                        List<String> focusAreas, List<String> suggestedQuestions) {
        return AgentDefinition.builder()
                .type(type)
                .name(name)
                .description(description)
                .icon(icon)
                .preferredModel(preferredModel)
                .fallbackModel(preferredModel.equals("claude") ? "gemini" : "claude")
                .focusAreas(focusAreas)
                .suggestedQuestions(suggestedQuestions)
                .build();
    }

    private boolean isDemoMode(HttpSession session) {
        Boolean demoMode = (Boolean) session.getAttribute("demoMode");
        return demoMode != null && demoMode;
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/controller/CopilotController.java
git commit -m "feat: add CopilotController with /api/copilot endpoints"
```

---

### Task 11: Add global exception handler

**Files:**
- Create: `src/main/java/com/stockman/exception/GlobalExceptionHandler.java`

**Step 1: Create GlobalExceptionHandler.java**

```java
package com.stockman.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_ERROR",
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred. Please try again.",
                "timestamp", Instant.now().toString()
        ));
    }
}
```

**Step 2: Compile to verify**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/stockman/exception/GlobalExceptionHandler.java
git commit -m "feat: add global exception handler for clean error responses"
```

---

## Phase 6: Frontend

### Task 12: Update api.js with copilot methods

**Files:**
- Modify: `src/main/resources/static/scripts/api.js`

**Step 1: Add copilot methods to the API object**

Add the following methods to the existing `API` object at the end, before the closing `};`:

```javascript
    // Copilot endpoints
    copilotAsk: async (request) => {
        const response = await fetch(`${API.baseUrl}/api/copilot/ask`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });
        return response.json();
    },

    copilotAnalyzeStock: async (symbol) => {
        const response = await fetch(`${API.baseUrl}/api/copilot/analyze/${symbol}`, {
            method: 'POST'
        });
        return response.json();
    },

    copilotPortfolioReview: async () => {
        const response = await fetch(`${API.baseUrl}/api/copilot/portfolio-review`, {
            method: 'POST'
        });
        return response.json();
    },

    copilotGetAgents: async () => {
        const response = await fetch(`${API.baseUrl}/api/copilot/agents`);
        return response.json();
    },

    copilotGetReasoning: async (requestId) => {
        const response = await fetch(`${API.baseUrl}/api/copilot/reasoning/${requestId}`);
        return response.json();
    },
```

**Step 2: Verify file is valid JS (no syntax errors)**

Open in browser or run a quick syntax check.

**Step 3: Commit**

```bash
git add src/main/resources/static/scripts/api.js
git commit -m "feat: add copilot API methods to frontend api.js"
```

---

### Task 13: Create copilot.html page

**Files:**
- Create: `src/main/resources/static/copilot.html`

**Step 1: Create copilot.html**

Follow exact patterns from `analysis.html` — sidebar, main content area, dark theme. New features: agent badges, reasoning chain cards, cross-model agreement indicator.

NOTE: The copilot.js script handles DOM rendering safely using textContent for user-provided data. Only structured data from the app's own API is rendered as HTML, following the same pattern as the existing analysis.html page.

See the separate copilot.html and copilot.js content in the design doc — they follow the existing app patterns for sidebar navigation, styling, and API integration.

**Step 2: Commit**

```bash
git add src/main/resources/static/copilot.html
git commit -m "feat: add copilot.html page"
```

---

### Task 14: Create copilot.js

**Files:**
- Create: `src/main/resources/static/scripts/copilot.js`

**Step 1: Create copilot.js**

The script handles:
- Loading holdings into the symbol dropdown
- Sending queries to the copilot API
- Rendering responses with recommendation badges, reasoning chain cards, trade plans
- Conversation history tracking
- Suggested question chips

Key functions:
- `init()` — loads holdings, sets up event listeners
- `sendQuery()` — sends to API.copilotAsk, renders response
- `renderResponse(response)` — builds the response UI with badges, agent cards, trade plan
- `toggleAgent(index)` — expands/collapses agent reasoning cards
- `useSuggestion(el)` — fills input with clicked suggestion
- `formatMarkdown(text)` — basic markdown to HTML (same pattern as existing app.js)

NOTE: Follow the same DOM rendering pattern as the existing `analysis.html` page — the app only renders its own structured API responses as HTML, not arbitrary user input.

**Step 2: Commit**

```bash
git add src/main/resources/static/scripts/copilot.js
git commit -m "feat: add copilot.js with query, rendering, and agent visualization"
```

---

### Task 15: Add Copilot link to navigation in all pages

**Files:**
- Modify: `src/main/resources/static/dashboard.html`
- Modify: `src/main/resources/static/holdings.html`
- Modify: `src/main/resources/static/analysis.html`
- Modify: `src/main/resources/static/strategy.html`

**Step 1: In each HTML file's sidebar `<ul class="nav-links">`, add the Copilot link**

Add after the Analysis `<li>` and before Strategy `<li>`:

```html
<li><a href="/copilot.html"><span class="nav-icon">C</span>Copilot</a></li>
```

**Step 2: Commit**

```bash
git add src/main/resources/static/dashboard.html \
        src/main/resources/static/holdings.html \
        src/main/resources/static/analysis.html \
        src/main/resources/static/strategy.html
git commit -m "feat: add Copilot link to sidebar navigation on all pages"
```

---

## Phase 7: Integration Testing & Verification

### Task 16: Manual smoke test

**Step 1: Start the app**

Run: `./mvnw spring-boot:run`
Expected: App starts on port 8080 without errors

**Step 2: Enable demo mode**

```bash
curl -X POST http://localhost:8080/api/auth/demo
```

**Step 3: Test copilot agents endpoint**

```bash
curl http://localhost:8080/api/copilot/agents | python3 -m json.tool
```
Expected: JSON array with 9 agents (all except Synthesizer which is internal)

**Step 4: Test copilot ask**

```bash
curl -X POST http://localhost:8080/api/copilot/ask \
  -H 'Content-Type: application/json' \
  -d '{"query": "Should I buy RELIANCE?", "symbol": "RELIANCE"}'
```
Expected: JSON response with recommendation, confidence, reasoningChain

**Step 5: Test portfolio review**

```bash
curl -X POST http://localhost:8080/api/copilot/portfolio-review
```
Expected: JSON response with portfolio analysis

**Step 6: Open browser**

Navigate to `http://localhost:8080/copilot.html`
- Verify page loads with dark theme
- Verify holdings dropdown is populated (after demo mode)
- Type a question and click "Ask Copilot"
- Verify response renders with badges, reasoning chain, etc.

**Step 7: Commit if all working**

```bash
git add -A
git commit -m "chore: integration verified - copilot feature complete"
```

---

## Summary of All New Files

| File | Purpose |
|------|---------|
| `model/AgentDefinition.java` | Agent configuration DTO with CopilotAgentType enum |
| `model/AgentResult.java` | Individual agent output DTO |
| `model/TradePlan.java` | Trade execution plan DTO |
| `model/OrchestratorResponse.java` | Full copilot response DTO |
| `model/CopilotRequest.java` | Copilot input DTO |
| `model/MarketData.java` | Enriched market data DTO |
| `model/NewsArticle.java` | News article DTO |
| `config/AnthropicConfig.java` | Claude API configuration |
| `config/FinnhubConfig.java` | Finnhub API configuration |
| `service/AIModelService.java` | Unified AI model interface |
| `service/ClaudeModelService.java` | Claude API implementation |
| `service/FinnhubService.java` | Finnhub news integration |
| `service/MarketDataService.java` | Market data enrichment |
| `service/IntentClassifier.java` | Query-to-agent routing |
| `service/OrchestratorService.java` | Core orchestrator brain |
| `controller/CopilotController.java` | REST endpoints |
| `exception/GlobalExceptionHandler.java` | Error handling |
| `prompts/CopilotPrompts.java` | All agent system prompts |
| `static/copilot.html` | Copilot UI page |
| `static/scripts/copilot.js` | Copilot UI logic |

## Modified Files

| File | Change |
|------|--------|
| `application.yml` | Add anthropic, finnhub, copilot config |
| `service/GeminiService.java` | Implement AIModelService interface |
| `static/scripts/api.js` | Add copilot API methods |
| `static/dashboard.html` | Add Copilot nav link |
| `static/holdings.html` | Add Copilot nav link |
| `static/analysis.html` | Add Copilot nav link |
| `static/strategy.html` | Add Copilot nav link |
