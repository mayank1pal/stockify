package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketData {
    private String symbol;
    private BigDecimal ltp;
    private BigDecimal dayChange;
    private BigDecimal dayChangePercent;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private BigDecimal weekHigh52;
    private BigDecimal weekLow52;
    private BigDecimal change30d;
    private BigDecimal change90d;
    private List<OhlcCandle> recentCandles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OhlcCandle {
        private String date;
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private long volume;
    }
}
