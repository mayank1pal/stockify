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
                            .sentiment("neutral")
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Error parsing news response: {}", e.getMessage());
        }
        return articles;
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
