package com.stockman.scanner.model;

public record IndicatorSnapshot(
    double ema9, double ema21, double ema50, double ema200,
    double rsi,
    double macdLine, double macdSignal, double macdHistogram,
    double stochasticK, double stochasticD,
    double vwap,
    double superTrend, boolean superTrendBullish,
    double obv,
    double rvol,
    double atr,
    boolean warmedUp
) {}
