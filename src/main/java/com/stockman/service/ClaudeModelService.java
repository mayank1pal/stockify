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
