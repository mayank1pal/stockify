package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Holding {
    private String tradingSymbol;
    private String exchange;
    private String isin;
    private int quantity;
    private BigDecimal averagePrice;
    private BigDecimal lastPrice;
    private BigDecimal closePrice;
    private BigDecimal pnl;
    private BigDecimal pnlPercentage;
    private BigDecimal dayChange;
    private BigDecimal dayChangePercentage;
    private BigDecimal investedValue;
    private BigDecimal currentValue;
}
