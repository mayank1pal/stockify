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
public class AnalysisResponse {
    private String answer;
    private double confidence;
    private List<String> sources;
    private List<String> followUpQuestions;
    private String agentType;
    private long timestamp;
}
