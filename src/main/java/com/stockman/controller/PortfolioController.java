package com.stockman.controller;

import com.stockman.model.Holding;
import com.stockman.model.PortfolioSummary;
import com.stockman.model.Position;
import com.stockman.service.PortfolioService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Slf4j
public class PortfolioController {

    private final PortfolioService portfolioService;

    @GetMapping("/summary")
    public ResponseEntity<PortfolioSummary> getPortfolioSummary(HttpSession session) {
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoPortfolioSummary());
        }
        
        PortfolioSummary summary = portfolioService.getPortfolioSummary(session.getId());
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/holdings")
    public ResponseEntity<List<Holding>> getHoldings(HttpSession session) {
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoHoldings());
        }
        
        List<Holding> holdings = portfolioService.getHoldings(session.getId());
        return ResponseEntity.ok(holdings);
    }

    @GetMapping("/positions")
    public ResponseEntity<List<Position>> getPositions(HttpSession session) {
        if (isDemoMode(session)) {
            return ResponseEntity.ok(new ArrayList<>());
        }
        
        List<Position> positions = portfolioService.getPositions(session.getId());
        return ResponseEntity.ok(positions);
    }

    @GetMapping("/holding/{symbol}")
    public ResponseEntity<Holding> getHoldingBySymbol(
            @PathVariable String symbol,
            HttpSession session) {
        
        if (isDemoMode(session)) {
            return getDemoHoldings().stream()
                    .filter(h -> h.getTradingSymbol().equalsIgnoreCase(symbol))
                    .findFirst()
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }
        
        Holding holding = portfolioService.getHoldingBySymbol(session.getId(), symbol);
        if (holding != null) {
            return ResponseEntity.ok(holding);
        }
        return ResponseEntity.notFound().build();
    }

    private boolean isDemoMode(HttpSession session) {
        Boolean demoMode = (Boolean) session.getAttribute("demoMode");
        return demoMode != null && demoMode;
    }

    // Demo data for testing without Zerodha credentials
    private List<Holding> getDemoHoldings() {
        return List.of(
                Holding.builder()
                        .tradingSymbol("RELIANCE")
                        .exchange("NSE")
                        .isin("INE002A01018")
                        .quantity(50)
                        .averagePrice(BigDecimal.valueOf(2450.00))
                        .lastPrice(BigDecimal.valueOf(2680.50))
                        .closePrice(BigDecimal.valueOf(2665.00))
                        .pnl(BigDecimal.valueOf(11525.00))
                        .pnlPercentage(BigDecimal.valueOf(9.41))
                        .dayChange(BigDecimal.valueOf(775.00))
                        .dayChangePercentage(BigDecimal.valueOf(0.58))
                        .investedValue(BigDecimal.valueOf(122500.00))
                        .currentValue(BigDecimal.valueOf(134025.00))
                        .build(),
                Holding.builder()
                        .tradingSymbol("TCS")
                        .exchange("NSE")
                        .isin("INE467B01029")
                        .quantity(30)
                        .averagePrice(BigDecimal.valueOf(3820.00))
                        .lastPrice(BigDecimal.valueOf(4050.25))
                        .closePrice(BigDecimal.valueOf(4020.00))
                        .pnl(BigDecimal.valueOf(6907.50))
                        .pnlPercentage(BigDecimal.valueOf(6.03))
                        .dayChange(BigDecimal.valueOf(907.50))
                        .dayChangePercentage(BigDecimal.valueOf(0.75))
                        .investedValue(BigDecimal.valueOf(114600.00))
                        .currentValue(BigDecimal.valueOf(121507.50))
                        .build(),
                Holding.builder()
                        .tradingSymbol("HDFCBANK")
                        .exchange("NSE")
                        .isin("INE040A01034")
                        .quantity(75)
                        .averagePrice(BigDecimal.valueOf(1680.00))
                        .lastPrice(BigDecimal.valueOf(1720.30))
                        .closePrice(BigDecimal.valueOf(1715.00))
                        .pnl(BigDecimal.valueOf(3022.50))
                        .pnlPercentage(BigDecimal.valueOf(2.40))
                        .dayChange(BigDecimal.valueOf(397.50))
                        .dayChangePercentage(BigDecimal.valueOf(0.31))
                        .investedValue(BigDecimal.valueOf(126000.00))
                        .currentValue(BigDecimal.valueOf(129022.50))
                        .build(),
                Holding.builder()
                        .tradingSymbol("INFY")
                        .exchange("NSE")
                        .isin("INE009A01021")
                        .quantity(60)
                        .averagePrice(BigDecimal.valueOf(1520.00))
                        .lastPrice(BigDecimal.valueOf(1485.50))
                        .closePrice(BigDecimal.valueOf(1490.00))
                        .pnl(BigDecimal.valueOf(-2070.00))
                        .pnlPercentage(BigDecimal.valueOf(-2.27))
                        .dayChange(BigDecimal.valueOf(-270.00))
                        .dayChangePercentage(BigDecimal.valueOf(-0.30))
                        .investedValue(BigDecimal.valueOf(91200.00))
                        .currentValue(BigDecimal.valueOf(89130.00))
                        .build(),
                Holding.builder()
                        .tradingSymbol("ICICIBANK")
                        .exchange("NSE")
                        .isin("INE090A01021")
                        .quantity(100)
                        .averagePrice(BigDecimal.valueOf(980.00))
                        .lastPrice(BigDecimal.valueOf(1045.75))
                        .closePrice(BigDecimal.valueOf(1040.00))
                        .pnl(BigDecimal.valueOf(6575.00))
                        .pnlPercentage(BigDecimal.valueOf(6.71))
                        .dayChange(BigDecimal.valueOf(575.00))
                        .dayChangePercentage(BigDecimal.valueOf(0.55))
                        .investedValue(BigDecimal.valueOf(98000.00))
                        .currentValue(BigDecimal.valueOf(104575.00))
                        .build(),
                Holding.builder()
                        .tradingSymbol("TATAMOTORS")
                        .exchange("NSE")
                        .isin("INE155A01022")
                        .quantity(150)
                        .averagePrice(BigDecimal.valueOf(720.00))
                        .lastPrice(BigDecimal.valueOf(895.40))
                        .closePrice(BigDecimal.valueOf(880.00))
                        .pnl(BigDecimal.valueOf(26310.00))
                        .pnlPercentage(BigDecimal.valueOf(24.36))
                        .dayChange(BigDecimal.valueOf(2310.00))
                        .dayChangePercentage(BigDecimal.valueOf(1.75))
                        .investedValue(BigDecimal.valueOf(108000.00))
                        .currentValue(BigDecimal.valueOf(134310.00))
                        .build()
        );
    }

    private PortfolioSummary getDemoPortfolioSummary() {
        List<Holding> holdings = getDemoHoldings();
        
        BigDecimal totalInvestment = holdings.stream()
                .map(Holding::getInvestedValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal currentValue = holdings.stream()
                .map(Holding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalPnl = holdings.stream()
                .map(Holding::getPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal dayChange = holdings.stream()
                .map(Holding::getDayChange)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PortfolioSummary.builder()
                .totalInvestment(totalInvestment)
                .currentValue(currentValue)
                .totalPnl(totalPnl)
                .totalPnlPercentage(BigDecimal.valueOf(7.67))
                .dayChange(dayChange)
                .dayChangePercentage(BigDecimal.valueOf(0.68))
                .totalHoldings(holdings.size())
                .profitableHoldings(5)
                .losingHoldings(1)
                .holdings(holdings)
                .positions(new ArrayList<>())
                .sectorAllocation(Map.of(
                        "Technology", BigDecimal.valueOf(29.5),
                        "Banking & Finance", BigDecimal.valueOf(32.7),
                        "Energy", BigDecimal.valueOf(18.8),
                        "Automobile", BigDecimal.valueOf(18.9)
                ))
                .topGainer(holdings.get(5)) // TATAMOTORS
                .topLoser(holdings.get(3))  // INFY
                .build();
    }
}
