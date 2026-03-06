package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrchestratorResponse {
    private String requestId;
    private String recommendation;
    private double confidence;
    private String summary;
    private List<AgentResult> reasoningChain;
    private TradePlan tradePlan;
    private boolean crossModelAgreement;
    private List<String> dissentingViews;
    private List<AgentDefinition.CopilotAgentType> agentsUsed;
    private List<AgentDefinition.CopilotAgentType> agentsSkipped;
    private List<String> followUpQuestions;
    private CostMetadata costMetadata;
    private Instant timestamp;
}
