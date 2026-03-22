package com.stockman.scanner.model;

import com.stockman.scanner.model.SignalEnums.DataQuality;
import java.time.LocalDate;

public record FundamentalData(
    String symbol,
    Long instrumentToken,
    String isin,
    String exchange,
    String sector,
    Integer lotSize,
    Double pe, Double eps, Double marketCap,
    Double high52w, Double low52w,
    Double dividendYield, Double bookValue,
    Double roe, Double debtToEquity,
    LocalDate lastUpdated,
    DataQuality quality
) {}
