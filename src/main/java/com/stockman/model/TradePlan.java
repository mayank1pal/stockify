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
public class TradePlan {
    private String symbol;
    private String action;
    private BigDecimal entryPrice;
    private BigDecimal targetPrice;
    private BigDecimal stopLoss;
    private String positionSize;
    private String timeframe;
    private String rationale;
}
