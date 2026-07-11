package com.stockman.scanner.model;

import java.time.Instant;

public record TickSnapshot(
    long instrumentToken,
    double ltp,
    double open, double high, double low,
    double previousClose,
    long volume,
    double bidPrice, double askPrice,
    Instant exchangeTimestamp,
    Instant receivedAt
) {}
