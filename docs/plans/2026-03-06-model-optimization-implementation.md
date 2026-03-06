# Model Optimization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Consolidate all LLM calls through OpenRouter with per-agent model selection and cost metadata tracking.

**Architecture:** Replace GeminiService + ClaudeModelService with a single OpenRouterModelService that accepts a model ID per call. Parse OpenRouter's `usage` block into LlmUsage DTOs. Aggregate costs in OrchestratorResponse. Update StockAnalyzer to use the new service.

**Tech Stack:** Spring Boot 3.2.2, Java 17, WebClient, OpenRouter OpenAI-compatible API, Lombok

**Build command:** `JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./mvnw compile -DskipTests -q`

**Run command:** `source .env && JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./mvnw spring-boot:run`

---

### Task 1: Create LlmUsage and CostMetadata DTOs

**Files:**
- Create: `src/main/java/com/stockman/model/LlmUsage.java`
- Create: `src/main/java/com/stockman/model/CostMetadata.java`

**Step 1: Create LlmUsage.java**

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
public class LlmUsage {
    private String model;
    private String generationId;
    private boolean isByok;
    private int promptTokens;
    private int completionTokens;
    private int reasoningTokens;
    private double cost;
    private double upstreamCost;
    private long latencyMs;
}
```

**Step 2: Create CostMetadata.java**

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
public class CostMetadata {
    private double totalCost;
    private int totalPromptTokens;
    private int totalCompletionTokens;
    private int totalReasoningTokens;
    private int byokCallCount;
    private int paidCallCount;
    private List<LlmUsage> agentCosts;
}
```

**Step 3: Add fields to existing DTOs**

In `AgentResult.java`, add:
```java
private LlmUsage llmUsage;
```

In `OrchestratorResponse.java`, add:
```java
private CostMetadata costMetadata;
```

**Step 4: Compile**

Run: `JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./mvnw compile -DskipTests -q`

**Step 5: Commit**

```bash
git add src/main/java/com/stockman/model/LlmUsage.java src/main/java/com/stockman/model/CostMetadata.java src/main/java/com/stockman/model/AgentResult.java src/main/java/com/stockman/model/OrchestratorResponse.java
git commit -m "feat: add LlmUsage and CostMetadata DTOs for cost tracking"
```

---

### Task 2: Create OpenRouterConfig and update application.yml

**Files:**
- Create: `src/main/java/com/stockman/config/OpenRouterConfig.java`
- Modify: `src/main/resources/application.yml`
- Delete: `src/main/java/com/stockman/config/GeminiConfig.java`
- Delete: `src/main/java/com/stockman/config/AnthropicConfig.java`

**Step 1: Update application.yml**

Replace the entire `gemini:` and `openrouter:` sections with:

```yaml
# OpenRouter Configuration (unified LLM gateway)
openrouter:
  api-key: ${OPENROUTER_API_KEY}
  base-url: https://openrouter.ai/api/v1
  default-model: google/gemini-2.5-flash
  fallback-model: google/gemini-2.5-flash
  max-tokens: 8192
  models:
    fundamental: google/gemini-2.5-flash
    technical: google/gemini-2.5-flash
    quantitative: qwen/qwen3-235b-a22b-2507
    sentiment: google/gemini-2.5-flash
    general: google/gemini-2.5-flash
    risk-assessor: qwen/qwq-32b
    portfolio-optimizer: google/gemini-2.5-flash
    geopolitical: qwen/qwen3-235b-a22b-2507
    trade-executor: qwen/qwq-32b
    synthesizer: qwen/qwen3-235b-a22b-2507
```

Remove the `gemini:` section entirely.

**Step 2: Create OpenRouterConfig.java**

```java
package com.stockman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Configuration
public class OpenRouterConfig {

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.base-url}")
    private String baseUrl;

    @Value("${openrouter.default-model}")
    private String defaultModel;

    @Value("${openrouter.fallback-model}")
    private String fallbackModel;

    @Value("${openrouter.max-tokens}")
    private int maxTokens;

    @Value("#{${openrouter.models}}")
    private Map<String, String> agentModels;

    @Bean
    public WebClient openRouterWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build())
                .build();
    }

    @Bean
    public String openRouterApiKey() {
        return apiKey;
    }

    @Bean
    public String openRouterDefaultModel() {
        return defaultModel;
    }

    @Bean
    public String openRouterFallbackModel() {
        return fallbackModel;
    }

    @Bean
    public int openRouterMaxTokens() {
        return maxTokens;
    }

    @Bean
    public Map<String, String> agentModelMap() {
        return agentModels;
    }
}
```

**Step 3: Delete old config files**

```bash
rm src/main/java/com/stockman/config/GeminiConfig.java
rm src/main/java/com/stockman/config/AnthropicConfig.java
```

**Step 4: Compile** (will fail — expected, services still reference old beans)

---

### Task 3: Create OpenRouterModelService

**Files:**
- Create: `src/main/java/com/stockman/service/OpenRouterModelService.java`
- Delete: `src/main/java/com/stockman/service/GeminiService.java`
- Delete: `src/main/java/com/stockman/service/ClaudeModelService.java`

**Step 1: Create OpenRouterModelService.java**

```java
package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.model.LlmUsage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class OpenRouterModelService implements AIModelService {

    private final WebClient webClient;
    private final String apiKey;
    private final String defaultModel;
    private final String fallbackModel;
    private final int maxTokens;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterModelService(WebClient openRouterWebClient,
                                  @Qualifier("openRouterApiKey") String apiKey,
                                  @Qualifier("openRouterDefaultModel") String defaultModel,
                                  @Qualifier("openRouterFallbackModel") String fallbackModel,
                                  @Qualifier("openRouterMaxTokens") int maxTokens) {
        this.webClient = openRouterWebClient;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.fallbackModel = fallbackModel;
        this.maxTokens = maxTokens;
    }

    @Override
    public String getName() {
        return "openrouter";
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt) {
        return analyzeWithModel(defaultModel, systemPrompt, userPrompt).getFirst();
    }

    @Override
    public CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> analyze(systemPrompt, userPrompt));
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.contains("placeholder");
    }

    /**
     * Analyze with a specific model ID. Returns the text response and LlmUsage metadata.
     */
    public AnalysisResult analyzeWithModel(String modelId, String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "max_tokens", maxTokens,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                )
            );

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(response, modelId);

        } catch (Exception e) {
            log.error("Error calling OpenRouter with model {}: {}", modelId, e.getMessage());

            // Try fallback model if different from requested
            if (!modelId.equals(fallbackModel)) {
                log.info("Falling back to {} for failed {} call", fallbackModel, modelId);
                try {
                    Map<String, Object> fallbackBody = Map.of(
                        "model", fallbackModel,
                        "max_tokens", maxTokens,
                        "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                        )
                    );

                    String response = webClient.post()
                            .uri("/chat/completions")
                            .bodyValue(fallbackBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();

                    return parseResponse(response, fallbackModel);
                } catch (Exception e2) {
                    log.error("Fallback model {} also failed: {}", fallbackModel, e2.getMessage());
                }
            }

            return new AnalysisResult(
                "Unable to perform analysis. OpenRouter error: " + e.getMessage(),
                LlmUsage.builder().model(modelId).build()
            );
        }
    }

    private AnalysisResult parseResponse(String response, String requestedModel) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // Extract text
            String text = "";
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                text = choices.get(0).path("message").path("content").asText();
            } else {
                log.warn("Unexpected OpenRouter response format: {}", response);
                text = "Unable to parse response.";
            }

            // Extract usage metadata
            JsonNode usage = root.path("usage");
            LlmUsage llmUsage = LlmUsage.builder()
                    .model(root.path("model").asText(requestedModel))
                    .generationId(root.path("id").asText(""))
                    .isByok(usage.path("is_byok").asBoolean(false))
                    .promptTokens(usage.path("prompt_tokens").asInt(0))
                    .completionTokens(usage.path("completion_tokens").asInt(0))
                    .reasoningTokens(usage.path("completion_tokens_details")
                            .path("reasoning_tokens").asInt(0))
                    .cost(usage.path("cost").asDouble(0.0))
                    .upstreamCost(usage.path("cost_details")
                            .path("upstream_inference_cost").asDouble(0.0))
                    .build();

            return new AnalysisResult(text, llmUsage);

        } catch (Exception e) {
            log.error("Error parsing OpenRouter response: {}", e.getMessage());
            return new AnalysisResult(
                "Unable to parse response.",
                LlmUsage.builder().model(requestedModel).build()
            );
        }
    }

    /**
     * Holds both the text response and usage metadata from an LLM call.
     */
    public record AnalysisResult(String text, LlmUsage usage) {
        public String getFirst() {
            return text;
        }
    }
}
```

**Step 2: Delete old service files**

```bash
rm src/main/java/com/stockman/service/GeminiService.java
rm src/main/java/com/stockman/service/ClaudeModelService.java
```

**Step 3: Compile** (will fail — OrchestratorService and StockAnalyzer still reference old services)

---

### Task 4: Update OrchestratorService for per-agent model routing and cost tracking

**Files:**
- Modify: `src/main/java/com/stockman/service/OrchestratorService.java`

**Step 1: Rewrite OrchestratorService**

Key changes:
- Replace `GeminiService` + `ClaudeModelService` with single `OpenRouterModelService`
- Replace `MODEL_ASSIGNMENT` map with injected `agentModelMap` from config
- Call `analyzeWithModel(modelId, ...)` to get both text and LlmUsage
- Build CostMetadata from collected LlmUsage entries
- Set `llmUsage` on each AgentResult
- Set `costMetadata` on OrchestratorResponse

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
    private final OpenRouterModelService modelService;
    private final MarketDataService marketDataService;
    private final PortfolioService portfolioService;
    private final Map<String, String> agentModelMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();

    @Value("${copilot.agent-timeout-seconds:30}")
    private int agentTimeoutSeconds;

    @Value("${copilot.max-concurrent-agents:6}")
    private int maxConcurrentAgents;

    @Value("${copilot.cache-ttl-minutes:15}")
    private int cacheTtlMinutes;

    @Value("${openrouter.default-model}")
    private String defaultModel;

    @Value("${openrouter.fallback-model}")
    private String fallbackModel;

    public OrchestratorService(IntentClassifier intentClassifier,
                                OpenRouterModelService modelService,
                                MarketDataService marketDataService,
                                PortfolioService portfolioService,
                                Map<String, String> agentModelMap) {
        this.intentClassifier = intentClassifier;
        this.modelService = modelService;
        this.marketDataService = marketDataService;
        this.portfolioService = portfolioService;
        this.agentModelMap = agentModelMap;
    }

    public OrchestratorResponse process(CopilotRequest request, String sessionId, boolean isDemoMode) {
        String requestId = UUID.randomUUID().toString();
        log.info("Processing copilot request [{}]: query='{}', symbol='{}'",
                requestId, request.getQuery(), request.getSymbol());

        String cacheKey = buildCacheKey(request);
        CachedResponse cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
            log.info("Returning cached response for request [{}]", requestId);
            return cached.response;
        }

        List<CopilotAgentType> agentsToInvoke = intentClassifier.classifyIntent(
                request.getQuery(), request.getSymbol());
        log.info("Intent classified: agents={}", agentsToInvoke);

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

        String userPrompt = buildUserPrompt(request, marketContext, portfolioContext);
        List<AgentResult> results = dispatchAgents(agentsToInvoke, userPrompt);

        List<AgentResult> successfulResults = results.stream()
                .filter(AgentResult::isSuccess)
                .toList();
        List<CopilotAgentType> skippedAgents = results.stream()
                .filter(r -> !r.isSuccess())
                .map(AgentResult::getAgentType)
                .toList();

        OrchestratorResponse response;
        if (intentClassifier.shouldSynthesize(agentsToInvoke) && successfulResults.size() >= 2) {
            response = synthesize(requestId, request.getQuery(), successfulResults,
                    results, agentsToInvoke, skippedAgents);
        } else if (!successfulResults.isEmpty()) {
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
                    .costMetadata(buildCostMetadata(results))
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
                    .costMetadata(buildCostMetadata(results))
                    .timestamp(Instant.now())
                    .build();
        }

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
        String modelId = resolveModelForAgent(agentType);

        OpenRouterModelService.AnalysisResult result = modelService.analyzeWithModel(
                modelId, systemPrompt, userPrompt);

        long duration = System.currentTimeMillis() - start;
        LlmUsage usage = result.usage();
        usage.setLatencyMs(duration);

        return AgentResult.builder()
                .agentType(agentType)
                .agentName(formatAgentName(agentType))
                .modelUsed(usage.getModel())
                .finding(result.text())
                .confidence(usage.isByok() ? 0.75 : 0.80)
                .success(true)
                .durationMs(duration)
                .llmUsage(usage)
                .build();
    }

    private String resolveModelForAgent(CopilotAgentType agentType) {
        String key = agentType.name().toLowerCase().replace("_", "-");
        return agentModelMap.getOrDefault(key, defaultModel);
    }

    private OrchestratorResponse synthesize(String requestId, String query,
                                             List<AgentResult> successfulResults,
                                             List<AgentResult> allResults,
                                             List<CopilotAgentType> agentsUsed,
                                             List<CopilotAgentType> agentsSkipped) {
        String synthesizerPrompt = CopilotPrompts.buildSynthesizerPrompt(query, successfulResults);
        String systemPrompt = CopilotPrompts.getSystemPrompt(CopilotAgentType.SYNTHESIZER);
        String synthModelId = resolveModelForAgent(CopilotAgentType.SYNTHESIZER);

        OpenRouterModelService.AnalysisResult synthResult = modelService.analyzeWithModel(
                synthModelId, systemPrompt, synthesizerPrompt);

        // Add synthesizer usage to results for cost tracking
        AgentResult synthAgent = AgentResult.builder()
                .agentType(CopilotAgentType.SYNTHESIZER)
                .agentName("Synthesizer")
                .modelUsed(synthResult.usage().getModel())
                .success(true)
                .llmUsage(synthResult.usage())
                .build();
        List<AgentResult> allWithSynth = new ArrayList<>(allResults);
        allWithSynth.add(synthAgent);

        return parseSynthesisResult(requestId, synthResult.text(), successfulResults,
                allWithSynth, agentsUsed, agentsSkipped);
    }

    private OrchestratorResponse parseSynthesisResult(String requestId, String synthesisResult,
                                                       List<AgentResult> reasoningChain,
                                                       List<AgentResult> allResults,
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
                    .costMetadata(buildCostMetadata(allResults))
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
                    .costMetadata(buildCostMetadata(allResults))
                    .timestamp(Instant.now())
                    .build();
        }
    }

    private CostMetadata buildCostMetadata(List<AgentResult> results) {
        List<LlmUsage> agentCosts = results.stream()
                .filter(r -> r.getLlmUsage() != null)
                .map(AgentResult::getLlmUsage)
                .toList();

        return CostMetadata.builder()
                .totalCost(agentCosts.stream().mapToDouble(LlmUsage::getCost).sum())
                .totalPromptTokens(agentCosts.stream().mapToInt(LlmUsage::getPromptTokens).sum())
                .totalCompletionTokens(agentCosts.stream().mapToInt(LlmUsage::getCompletionTokens).sum())
                .totalReasoningTokens(agentCosts.stream().mapToInt(LlmUsage::getReasoningTokens).sum())
                .byokCallCount((int) agentCosts.stream().filter(LlmUsage::isByok).count())
                .paidCallCount((int) agentCosts.stream().filter(u -> !u.isByok()).count())
                .agentCosts(agentCosts)
                .build();
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

**Step 2: Compile** (will fail — StockAnalyzer still uses GeminiService)

---

### Task 5: Update StockAnalyzer to use OpenRouterModelService

**Files:**
- Modify: `src/main/java/com/stockman/service/StockAnalyzer.java`

**Step 1: Replace GeminiService with OpenRouterModelService**

Change the field and all calls from `geminiService.analyzeWithPrompt(system, prompt)` to `modelService.analyze(system, prompt)`.

Replace:
```java
private final GeminiService geminiService;
```
With:
```java
private final OpenRouterModelService modelService;
```

Replace all occurrences of:
```java
geminiService.analyzeWithPrompt(...)
```
With:
```java
modelService.analyze(...)
```

There are 6 calls to replace (lines 39, 50, 65, 79, 104, 159 in the current file).

**Step 2: Compile**

Run: `JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./mvnw compile -DskipTests -q`

Expected: SUCCESS — all old references resolved.

**Step 3: Commit all changes from Tasks 2-5**

```bash
git add -A src/main/java/com/stockman/ src/main/resources/application.yml
git commit -m "feat: consolidate all LLM calls through OpenRouter with per-agent model routing

- Replace GeminiService + ClaudeModelService with single OpenRouterModelService
- Per-agent model selection via application.yml config map
- Parse OpenRouter usage block into LlmUsage DTOs
- Aggregate costs into CostMetadata on OrchestratorResponse
- Update StockAnalyzer to use new unified service
- Remove GeminiConfig and AnthropicConfig"
```

---

### Task 6: Update CopilotController agent definitions

**Files:**
- Modify: `src/main/java/com/stockman/controller/CopilotController.java`

**Step 1: Update agent definitions to show actual model IDs**

Inject `agentModelMap` and use it in `buildAgent()`:

Add field:
```java
private final Map<String, String> agentModelMap;
```

Update the constructor (it uses `@RequiredArgsConstructor`, so just add the field and Lombok handles it).

Update each `buildAgent(...)` call to use the actual model ID from the map. For example:
```java
buildAgent(CopilotAgentType.FUNDAMENTAL, "Fundamental Analyst",
    "Expert in company financials, valuation, and long-term value investing",
    "chart-bar", agentModelMap.getOrDefault("fundamental", "google/gemini-2.5-flash"),
    ...)
```

Update `buildAgent` method — change `fallbackModel` logic:
```java
.fallbackModel("google/gemini-2.5-flash")
```
(Always use BYOK Gemini as fallback, since it's free and always available.)

**Step 2: Compile and commit**

```bash
git add src/main/java/com/stockman/controller/CopilotController.java
git commit -m "feat: show actual model IDs in copilot agent definitions"
```

---

### Task 7: Update .env and remove GEMINI_API_KEY

**Files:**
- Modify: `.env`

**Step 1: Update .env**

Remove `GEMINI_API_KEY` (no longer needed — Gemini goes through OpenRouter BYOK).
Keep `OPENROUTER_API_KEY` and `FINNHUB_API_KEY`.

Remove `GEMINI_API_KEY` line. Keep `OPENROUTER_API_KEY` and `FINNHUB_API_KEY`.
Remove `ZERODHA_API_KEY` and `ZERODHA_API_SECRET` placeholders if unused.

**Step 2: No git action** (.env is gitignored)

---

### Task 8: Smoke test

**Step 1: Start the app**

```bash
source .env && JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home ./mvnw spring-boot:run
```

**Step 2: Enable demo mode**

```bash
curl -s -X POST http://localhost:8080/api/auth/demo -c /tmp/cookies.txt
```

**Step 3: Test copilot with cost metadata**

```bash
curl -s -X POST http://localhost:8080/api/copilot/ask \
  -H "Content-Type: application/json" \
  -b /tmp/cookies.txt \
  -d '{"query": "What are the risks of RELIANCE?", "symbol": "RELIANCE"}' | python3 -m json.tool
```

**Expected:** Response includes `costMetadata` with:
- `totalCost` (should be small, ~$0.001)
- `byokCallCount` (should be > 0 for Gemini agents)
- `paidCallCount` (should be > 0 for Qwen agents)
- `agentCosts` array with per-agent LlmUsage

**Step 4: Verify agents endpoint shows model IDs**

```bash
curl -s http://localhost:8080/api/copilot/agents | python3 -m json.tool
```

**Expected:** Each agent shows its actual model ID (e.g., `google/gemini-2.5-flash`, `qwen/qwq-32b`).

**Step 5: Test old analysis page still works**

```bash
curl -s -X POST http://localhost:8080/api/analysis/custom \
  -H "Content-Type: application/json" \
  -b /tmp/cookies.txt \
  -d '{"prompt": "Analyze RELIANCE", "symbol": "RELIANCE", "agentType": "FUNDAMENTAL"}' | python3 -m json.tool
```

**Expected:** Returns AnalysisResponse (demo mode response is fine).

**Step 6: Commit if any fixes were needed**

```bash
git add -A src/ && git commit -m "fix: resolve smoke test issues"
```
