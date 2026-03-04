package com.stockman.service;

import com.stockman.model.Holding;
import com.stockman.model.MarketData;
import com.stockman.model.NewsArticle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final PortfolioService portfolioService;
    private final FinnhubService finnhubService;

    public MarketData getMarketData(String sessionId, String symbol, boolean isDemoMode) {
        if (isDemoMode) {
            return getDemoMarketData(symbol);
        }

        Holding holding = portfolioService.getHoldingBySymbol(sessionId, symbol);
        if (holding == null) {
            return MarketData.builder()
                    .symbol(symbol)
                    .build();
        }

        return MarketData.builder()
                .symbol(symbol)
                .ltp(holding.getLastPrice())
                .dayChange(holding.getDayChange())
                .dayChangePercent(holding.getDayChangePercentage())
                .close(holding.getClosePrice())
                .build();
    }

    public List<NewsArticle> getNews(String symbol, boolean isDemoMode) {
        if (isDemoMode) {
            return getDemoNews(symbol);
        }
        return finnhubService.getCompanyNews(symbol, 7);
    }

    public String buildMarketContext(String sessionId, String symbol, boolean isDemoMode) {
        MarketData data = getMarketData(sessionId, symbol, isDemoMode);
        List<NewsArticle> news = getNews(symbol, isDemoMode);

        StringBuilder ctx = new StringBuilder();
        ctx.append("Current Market Data for ").append(symbol).append(":\n");

        if (data.getLtp() != null) {
            ctx.append(String.format("- LTP: Rs.%s", data.getLtp()));
            if (data.getDayChangePercent() != null) {
                ctx.append(String.format(" | Day Change: %s%%", data.getDayChangePercent()));
            }
            ctx.append("\n");
        }
        if (data.getWeekHigh52() != null && data.getWeekLow52() != null) {
            ctx.append(String.format("- 52-week: Rs.%s - Rs.%s\n", data.getWeekLow52(), data.getWeekHigh52()));
        }
        if (data.getVolume() > 0) {
            ctx.append(String.format("- Volume: %,d\n", data.getVolume()));
        }

        ctx.append("\n");
        ctx.append(finnhubService.formatNewsForPrompt(news));

        return ctx.toString();
    }

    public String buildPortfolioContext(String sessionId, boolean isDemoMode, List<Holding> holdings) {
        StringBuilder ctx = new StringBuilder("Portfolio Holdings:\n");
        for (Holding h : holdings) {
            ctx.append(String.format("- %s: Qty=%d, AvgPrice=Rs.%s, LTP=Rs.%s, P&L=Rs.%s (%.1f%%)\n",
                    h.getTradingSymbol(),
                    h.getQuantity(),
                    h.getAveragePrice(),
                    h.getLastPrice(),
                    h.getPnl(),
                    h.getPnlPercentage().doubleValue()));
        }
        return ctx.toString();
    }

    private MarketData getDemoMarketData(String symbol) {
        return MarketData.builder()
                .symbol(symbol)
                .ltp(new BigDecimal("2456.80"))
                .dayChange(new BigDecimal("29.15"))
                .dayChangePercent(new BigDecimal("1.2"))
                .open(new BigDecimal("2430.00"))
                .high(new BigDecimal("2470.50"))
                .low(new BigDecimal("2425.00"))
                .close(new BigDecimal("2427.65"))
                .volume(4200000L)
                .weekHigh52(new BigDecimal("2890.00"))
                .weekLow52(new BigDecimal("2180.00"))
                .build();
    }

    private List<NewsArticle> getDemoNews(String symbol) {
        return List.of(
                NewsArticle.builder()
                        .headline(symbol + " reports strong quarterly results, beats estimates")
                        .source("Economic Times")
                        .datetime(System.currentTimeMillis() / 1000 - 86400 * 2)
                        .sentiment("positive")
                        .build(),
                NewsArticle.builder()
                        .headline("Analysts upgrade " + symbol + " on growth outlook")
                        .source("Moneycontrol")
                        .datetime(System.currentTimeMillis() / 1000 - 86400 * 5)
                        .sentiment("positive")
                        .build()
        );
    }
}
