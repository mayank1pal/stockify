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
public class StockInsight {
    private String symbol;
    private String companyName;
    private LocalDateTime analysisDate;
    private AnalysisType analysisType;
    
    // Overall assessment
    private String overallRating; // STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL
    private int confidenceScore; // 1-100
    private String summary;
    
    // Detailed analysis sections
    private String fundamentalAnalysis;
    private String technicalAnalysis;
    private String riskAssessment;
    private String growthPotential;
    
    // Actionable insights
    private List<String> keyStrengths;
    private List<String> keyRisks;
    private List<String> recommendations;
    
    // Price targets
    private String shortTermOutlook; // 1-3 months
    private String mediumTermOutlook; // 3-12 months
    private String longTermOutlook; // 1-5 years
    
    public enum AnalysisType {
        LONG_TERM,
        SHORT_TERM,
        COMPREHENSIVE
    }
}
