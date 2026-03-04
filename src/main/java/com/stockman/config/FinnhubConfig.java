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
