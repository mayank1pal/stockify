package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.config.OpenRouterConfig;
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
    private final String defaultModel;
    private final String fallbackModel;
    private final int maxTokens;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterModelService(@Qualifier("openRouterWebClient") WebClient webClient,
                                  OpenRouterConfig config) {
        this.webClient = webClient;
        this.defaultModel = config.getDefaultModel();
        this.fallbackModel = config.getFallbackModel();
        this.maxTokens = config.getMaxTokens();
    }

    @Override
    public String getName() {
        return "openrouter";
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt) {
        return analyzeWithModel(defaultModel, systemPrompt, userPrompt).text();
    }

    @Override
    public CompletableFuture<String> analyzeAsync(String systemPrompt, String userPrompt) {
        return CompletableFuture.supplyAsync(() -> analyze(systemPrompt, userPrompt));
    }

    @Override
    public boolean isAvailable() {
        return true;
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

            String text = "";
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                text = choices.get(0).path("message").path("content").asText();
            } else {
                log.warn("Unexpected OpenRouter response format: {}", response);
                text = "Unable to parse response.";
            }

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

    public record AnalysisResult(String text, LlmUsage usage) {}
}
