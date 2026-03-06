package com.stockman.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "openrouter")
@Getter
@Setter
public class OpenRouterConfig {

    private String apiKey;
    private String baseUrl;
    private String defaultModel;
    private String fallbackModel;
    private int maxTokens;
    private Map<String, String> models = new HashMap<>();

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
    public Map<String, String> agentModelMap() {
        return models;
    }
}
