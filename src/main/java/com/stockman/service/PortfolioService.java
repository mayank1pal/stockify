package com.stockman.service;

import com.stockman.model.Holding;
import com.stockman.model.PortfolioSummary;
import com.stockman.model.Position;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioService {

    private final ZerodhaService zerodhaService;

    public PortfolioSummary getPortfolioSummary(String sessionId) {
        List<Holding> holdings = zerodhaService.getHoldings(sessionId);
        List<Position> positions = zerodhaService.getPositions(sessionId);

        if (holdings.isEmpty()) {
            return PortfolioSummary.builder()
                    .totalInvestment(BigDecimal.ZERO)
                    .currentValue(BigDecimal.ZERO)
                    .totalPnl(BigDecimal.ZERO)
                    .totalPnlPercentage(BigDecimal.ZERO)
                    .dayChange(BigDecimal.ZERO)
                    .dayChangePercentage(BigDecimal.ZERO)
                    .totalHoldings(0)
                    .profitableHoldings(0)
                    .losingHoldings(0)
                    .holdings(holdings)
                    .positions(positions)
                    .sectorAllocation(new HashMap<>())
                    .build();
        }

        // Calculate totals
        BigDecimal totalInvestment = holdings.stream()
                .map(Holding::getInvestedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentValue = holdings.stream()
                .map(Holding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnl = holdings.stream()
                .map(Holding::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnlPercentage = totalInvestment.compareTo(BigDecimal.ZERO) != 0
                ? totalPnl.multiply(BigDecimal.valueOf(100)).divide(totalInvestment, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal dayChange = holdings.stream()
                .map(Holding::getDayChange)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal previousValue = currentValue.subtract(dayChange);
        BigDecimal dayChangePercentage = previousValue.compareTo(BigDecimal.ZERO) != 0
                ? dayChange.multiply(BigDecimal.valueOf(100)).divide(previousValue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int profitableHoldings = (int) holdings.stream()
                .filter(h -> h.getPnl().compareTo(BigDecimal.ZERO) > 0)
                .count();

        int losingHoldings = (int) holdings.stream()
                .filter(h -> h.getPnl().compareTo(BigDecimal.ZERO) < 0)
                .count();

        // Find top gainer and loser
        Holding topGainer = holdings.stream()
                .max(Comparator.comparing(Holding::getPnlPercentage))
                .orElse(null);

        Holding topLoser = holdings.stream()
                .min(Comparator.comparing(Holding::getPnlPercentage))
                .orElse(null);

        // Calculate sector allocation (placeholder - would need sector data)
        Map<String, BigDecimal> sectorAllocation = calculateSectorAllocation(holdings, currentValue);

        return PortfolioSummary.builder()
                .totalInvestment(totalInvestment)
                .currentValue(currentValue)
                .totalPnl(totalPnl)
                .totalPnlPercentage(totalPnlPercentage)
                .dayChange(dayChange)
                .dayChangePercentage(dayChangePercentage)
                .totalHoldings(holdings.size())
                .profitableHoldings(profitableHoldings)
                .losingHoldings(losingHoldings)
                .holdings(holdings)
                .positions(positions)
                .sectorAllocation(sectorAllocation)
                .topGainer(topGainer)
                .topLoser(topLoser)
                .build();
    }

    public List<Holding> getHoldings(String sessionId) {
        return zerodhaService.getHoldings(sessionId);
    }

    public List<Position> getPositions(String sessionId) {
        return zerodhaService.getPositions(sessionId);
    }

    public Holding getHoldingBySymbol(String sessionId, String symbol) {
        return zerodhaService.getHoldings(sessionId).stream()
                .filter(h -> h.getTradingSymbol().equalsIgnoreCase(symbol))
                .findFirst()
                .orElse(null);
    }

    private Map<String, BigDecimal> calculateSectorAllocation(List<Holding> holdings, BigDecimal totalValue) {
        // Simplified sector allocation based on exchange
        // In production, you'd use a sector mapping service
        Map<String, BigDecimal> allocation = new HashMap<>();
        
        if (totalValue.compareTo(BigDecimal.ZERO) == 0) {
            return allocation;
        }

        for (Holding holding : holdings) {
            String sector = categorizeBySector(holding.getTradingSymbol());
            BigDecimal weight = holding.getCurrentValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalValue, 2, RoundingMode.HALF_UP);
            allocation.merge(sector, weight, BigDecimal::add);
        }

        return allocation;
    }

    private String categorizeBySector(String symbol) {
        // Simplified categorization - in production, use proper sector data
        String upperSymbol = symbol.toUpperCase();
        
        if (upperSymbol.contains("BANK") || upperSymbol.contains("FIN")) {
            return "Banking & Finance";
        } else if (upperSymbol.contains("IT") || upperSymbol.contains("TECH") || 
                   upperSymbol.contains("INFY") || upperSymbol.contains("TCS") || 
                   upperSymbol.contains("WIPRO")) {
            return "Technology";
        } else if (upperSymbol.contains("PHARMA") || upperSymbol.contains("HEALTH") ||
                   upperSymbol.contains("SUN") || upperSymbol.contains("CIPLA")) {
            return "Healthcare";
        } else if (upperSymbol.contains("RELIANCE") || upperSymbol.contains("OIL") ||
                   upperSymbol.contains("ONGC") || upperSymbol.contains("BPCL")) {
            return "Energy";
        } else if (upperSymbol.contains("AUTO") || upperSymbol.contains("TATA") ||
                   upperSymbol.contains("MARUTI") || upperSymbol.contains("HERO")) {
            return "Automobile";
        } else {
            return "Others";
        }
    }
}
