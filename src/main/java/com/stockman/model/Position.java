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
public class Position {
    private String tradingSymbol;
    private String exchange;
    private String product;
    private int quantity;
    private int overnightQuantity;
    private int multiplier;
    private BigDecimal averagePrice;
    private BigDecimal closePrice;
    private BigDecimal lastPrice;
    private BigDecimal value;
    private BigDecimal pnl;
    private BigDecimal m2m;
    private BigDecimal unrealised;
    private BigDecimal realised;
    private int buyQuantity;
    private int sellQuantity;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private BigDecimal buyValue;
    private BigDecimal sellValue;
}
