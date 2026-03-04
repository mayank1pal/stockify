package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequest {
    private String symbol;
    private String prompt;
    private AgentType agentType;
    private List<ConversationMessage> conversationHistory;
    
    public enum AgentType {
        FUNDAMENTAL,
        TECHNICAL,
        QUANTITATIVE,
        SENTIMENT,
        GENERAL
    }
}
