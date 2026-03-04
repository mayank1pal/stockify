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
