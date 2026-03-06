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
public class CostMetadata {
    private double totalCost;
    private int totalPromptTokens;
    private int totalCompletionTokens;
    private int totalReasoningTokens;
    private int byokCallCount;
    private int paidCallCount;
    private List<LlmUsage> agentCosts;
}
