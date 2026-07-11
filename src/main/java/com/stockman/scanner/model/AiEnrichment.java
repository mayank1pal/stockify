package com.stockman.scanner.model;

import java.time.Instant;

public record AiEnrichment(
    String signalId,
    String insight,
    double aiConfidence,
    String[] agentsUsed,
    Instant completedAt
) {}
