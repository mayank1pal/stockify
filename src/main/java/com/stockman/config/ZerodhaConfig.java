package com.stockman.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;

@Configuration
@Getter
public class ZerodhaConfig {

    @Value("${zerodha.api-key}")
    private String apiKey;

    @Value("${zerodha.api-secret}")
    private String apiSecret;

    @Value("${zerodha.redirect-url}")
    private String redirectUrl;

    @Value("${zerodha.login-url}")
    private String loginUrl;

    public String getLoginUrl() {
        return loginUrl + "?api_key=" + apiKey + "&v=3";
    }
}
