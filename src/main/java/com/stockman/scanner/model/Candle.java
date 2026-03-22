package com.stockman.scanner.model;

import java.time.Instant;

public record Candle(
    long instrumentToken,
    Instant openTime,
    double open, double high, double low, double close,
    long volume,
    double vwap
) {}
