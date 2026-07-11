package com.stockman.scanner.model;

import java.time.Instant;
import java.util.Set;

public record Watchlist(
    Set<String> symbols,
    Instant lastModified
) {}
