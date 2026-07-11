package com.stockman.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "scanner")
@Validated
@Getter @Setter
public class ScannerConfig {
    private boolean enabled = true;
    private String connectTime = "09:00";
    private String disconnectTime = "15:35";
    private String silencePeriodEnd = "09:16:30";
    private int warmupMinutes = 30;
    private String holidayCalendar = "classpath:holidays.json";
    private int candleWindowSize = 600;
    private int tickQueueCapacity = 10000;

    private Reconnect reconnect = new Reconnect();
    private Cooldown cooldown = new Cooldown();
    private AiEnrichment aiEnrichment = new AiEnrichment();

    @Getter @Setter
    public static class Reconnect {
        private int initialDelaySeconds = 5;
        private int maxDelaySeconds = 60;
        private double multiplier = 2.0;
    }

    @Getter @Setter
    public static class Cooldown {
        private int scalpMinutes = 5;
        private int intradayMinutes = 15;
        private int swingMinutes = 60;
    }

    @Getter @Setter
    public static class AiEnrichment {
        private boolean enabled = true;
        private int maxCallsPerMinute = 10;
        private int timeoutSeconds = 15;
        private int swingGateTimeoutSeconds = 15;
    }
}
