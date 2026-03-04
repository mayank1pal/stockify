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
public class AgentDefinition {
    private CopilotAgentType type;
    private String name;
    private String description;
    private String icon;
    private String preferredModel;
    private String fallbackModel;
    private List<String> focusAreas;
    private List<String> suggestedQuestions;

    public enum CopilotAgentType {
        FUNDAMENTAL,
        TECHNICAL,
        QUANTITATIVE,
        SENTIMENT,
        GENERAL,
        RISK_ASSESSOR,
        PORTFOLIO_OPTIMIZER,
        GEOPOLITICAL,
        TRADE_EXECUTOR,
        SYNTHESIZER
    }
}
