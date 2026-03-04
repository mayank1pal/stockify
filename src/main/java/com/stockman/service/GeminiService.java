package com.stockman.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
public class GeminiService implements AIModelService {

    private final WebClient geminiWebClient;
    private final String apiKey;

    @Value("${gemini.model:gemini-pro}")
    private String model;

    public GeminiService(WebClient geminiWebClient, @Qualifier("geminiApiKey") String apiKey) {
        this.geminiWebClient = geminiWebClient;
        this.apiKey = apiKey;
    }

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
