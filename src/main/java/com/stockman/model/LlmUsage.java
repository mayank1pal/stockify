package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {
    private String model;
    private String generationId;
    private boolean isByok;
    private int promptTokens;
    private int completionTokens;
    private int reasoningTokens;
    private double cost;
    private double upstreamCost;
    private long latencyMs;
}
