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

    private final ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();

    @Value("${copilot.agent-timeout-seconds:30}")
    private int agentTimeoutSeconds;

    @Value("${copilot.max-concurrent-agents:6}")
    private int maxConcurrentAgents;

    @Value("${copilot.cache-ttl-minutes:15}")
    private int cacheTtlMinutes;

    private static final Map<CopilotAgentType, String> MODEL_ASSIGNMENT = Map.of(
            CopilotAgentType.FUNDAMENTAL, "gemini",
            CopilotAgentType.TECHNICAL, "gemini",
            CopilotAgentType.QUANTITATIVE, "gemini",
            CopilotAgentType.SENTIMENT, "gemini",
            CopilotAgentType.GENERAL, "gemini",
            CopilotAgentType.RISK_ASSESSOR, "openrouter",
            CopilotAgentType.PORTFOLIO_OPTIMIZER, "gemini",
            CopilotAgentType.GEOPOLITICAL, "openrouter",
            CopilotAgentType.TRADE_EXECUTOR, "openrouter"
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
                    agentsToInvoke, skippedAgents);
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
                    .confidence(0.75)
                    .success(true)
                    .durationMs(duration)
                    .build();
        } catch (Exception e) {
            log.error("Agent {} failed on model {}: {}", agentType, modelUsed, e.getMessage());

            AIModelService fallback = modelUsed.equals("openrouter") ? geminiService : claudeModelService;
            if (fallback.isAvailable()) {
                try {
                    String result = fallback.analyze(systemPrompt, userPrompt);
                    long duration = System.currentTimeMillis() - start;
                    return AgentResult.builder()
                            .agentType(agentType)
                            .agentName(formatAgentName(agentType))
                            .modelUsed(fallback.getName())
                            .finding(result)
                            .confidence(0.65)
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
        if ("openrouter".equals(preferredModel) && claudeModelService.isAvailable()) {
            return claudeModelService;
        }
        if ("gemini".equals(preferredModel) && geminiService.isAvailable()) {
            return geminiService;
        }
        if (claudeModelService.isAvailable()) return claudeModelService;
        if (geminiService.isAvailable()) return geminiService;
        return geminiService;
    }

    private OrchestratorResponse synthesize(String requestId, String query,
                                             List<AgentResult> results,
                                             List<CopilotAgentType> agentsUsed,
                                             List<CopilotAgentType> agentsSkipped) {
        String synthesizerPrompt = CopilotPrompts.buildSynthesizerPrompt(query, results);
        String systemPrompt = CopilotPrompts.getSystemPrompt(CopilotAgentType.SYNTHESIZER);

        AIModelService synthModel = claudeModelService.isAvailable() ? claudeModelService : geminiService;
        String synthesisResult = synthModel.analyze(systemPrompt, synthesizerPrompt);

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
