package com.stockman.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSummary {
    private BigDecimal totalInvestment;
    private BigDecimal currentValue;
    private BigDecimal totalPnl;
    private BigDecimal totalPnlPercentage;
    private BigDecimal dayChange;
    private BigDecimal dayChangePercentage;
    private int totalHoldings;
    private int profitableHoldings;
    private int losingHoldings;
    private List<Holding> holdings;
    private List<Position> positions;
    private Map<String, BigDecimal> sectorAllocation;
    private Holding topGainer;
    private Holding topLoser;
}
