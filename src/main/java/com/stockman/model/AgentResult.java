package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    private AgentDefinition.CopilotAgentType agentType;
    private String agentName;
    private String modelUsed;
    private String finding;
    private double confidence;
    private boolean success;
    private String errorMessage;
    private long durationMs;
    private LlmUsage llmUsage;
}
