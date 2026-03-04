package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentStrategy {
    private StrategyType strategyType;
    private LocalDateTime generatedAt;
    private String summary;
    
    // Portfolio-level recommendations
    private String portfolioHealthScore; // A, B, C, D, F
    private String diversificationScore;
    private String riskLevel; // LOW, MEDIUM, HIGH
    
    // Actionable strategies
    private List<StrategyRecommendation> recommendations;
    private List<String> stocksToBuy;
    private List<String> stocksToSell;
    private List<String> stocksToHold;
    
    // Rebalancing suggestions
    private List<RebalanceAction> rebalanceActions;
    
    // Risk management
    private List<String> riskMitigationSteps;
    private String stopLossStrategy;
    
    // Growth opportunities
    private List<String> sectorOpportunities;
    private List<String> emergingTrends;
    
    public enum StrategyType {
        LONG_TERM_GROWTH,
        SHORT_TERM_TRADING,
        VALUE_INVESTING,
        DIVIDEND_INCOME,
        BALANCED
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StrategyRecommendation {
        private String title;
        private String description;
        private String priority; // HIGH, MEDIUM, LOW
        private String expectedImpact;
        private String timeframe;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RebalanceAction {
        private String symbol;
        private String action; // BUY, SELL, REDUCE, INCREASE
        private String currentWeight;
        private String targetWeight;
        private String reason;
    }
}
