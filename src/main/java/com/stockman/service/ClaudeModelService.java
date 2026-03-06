package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class ClaudeModelService implements AIModelService {

    private final WebClient anthropicWebClient;
    private final String apiKey;
    private final String model;

    public ClaudeModelService(WebClient anthropicWebClient,
                              @Qualifier("anthropicApiKey") String apiKey,
                              @Qualifier("anthropicModel") String model) {
        this.anthropicWebClient = anthropicWebClient;
        this.apiKey = apiKey;
        this.model = model;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "openrouter";
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 8192,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                )
            );

            String response = anthropicWebClient.post()
                    .uri("/chat/completions")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return extractTextFromResponse(response);

        } catch (Exception e) {
            log.error("Error calling OpenRouter API: {}", e.getMessage());
            return "Unable to perform analysis. OpenRouter API error: " + e.getMessage();
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
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("message").path("content").asText();
            }
            log.warn("Unexpected OpenRouter response format: {}", response);
            return "Unable to parse OpenRouter response.";
        } catch (Exception e) {
            log.error("Error parsing OpenRouter response: {}", e.getMessage());
            return "Unable to parse OpenRouter response.";
        }
    }
}
