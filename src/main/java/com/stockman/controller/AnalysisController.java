package com.stockman.controller;

import com.stockman.model.InvestmentStrategy;
import com.stockman.model.StockInsight;
import com.stockman.service.StockAnalyzer;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Slf4j
public class AnalysisController {

    private final StockAnalyzer stockAnalyzer;

    @PostMapping("/stock/{symbol}")
    public ResponseEntity<StockInsight> analyzeStock(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "COMPREHENSIVE") String type,
            HttpSession session) {
        
        log.info("Analyzing stock: {} with type: {}", symbol, type);
        
        StockInsight.AnalysisType analysisType = parseAnalysisType(type);
        
        // Check for demo mode
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoStockInsight(symbol, analysisType));
        }
        
        StockInsight insight = stockAnalyzer.analyzeStock(session.getId(), symbol, analysisType);
        return ResponseEntity.ok(insight);
    }

    @PostMapping("/portfolio")
    public ResponseEntity<InvestmentStrategy> analyzePortfolio(HttpSession session) {
        log.info("Analyzing portfolio");
        
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoPortfolioAnalysis());
        }
        
        InvestmentStrategy strategy = stockAnalyzer.analyzePortfolio(session.getId());
        return ResponseEntity.ok(strategy);
    }

    @PostMapping("/strategy/long-term")
    public ResponseEntity<InvestmentStrategy> getLongTermStrategy(
            @RequestBody Map<String, String> request,
            HttpSession session) {
        
        String riskTolerance = request.getOrDefault("riskTolerance", "MEDIUM");
        String primaryGoal = request.getOrDefault("primaryGoal", "WEALTH_CREATION");
        
        log.info("Generating long-term strategy with risk: {} and goal: {}", riskTolerance, primaryGoal);
        
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoLongTermStrategy());
        }
        
        InvestmentStrategy strategy = stockAnalyzer.generateLongTermStrategy(
                session.getId(), riskTolerance, primaryGoal);
        return ResponseEntity.ok(strategy);
    }

    @PostMapping("/strategy/short-term")
    public ResponseEntity<InvestmentStrategy> getShortTermStrategy(HttpSession session) {
        log.info("Generating short-term strategy");
        
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoShortTermStrategy());
        }
        
        InvestmentStrategy strategy = stockAnalyzer.generateShortTermStrategy(session.getId());
        return ResponseEntity.ok(strategy);
    }

    @PostMapping("/custom")
    public ResponseEntity<com.stockman.model.AnalysisResponse> customAnalysis(
            @RequestBody com.stockman.model.AnalysisRequest request,
            HttpSession session) {
        
        log.info("Custom analysis for symbol: {} with agent: {}", request.getSymbol(), request.getAgentType());
        
        if (isDemoMode(session)) {
            return ResponseEntity.ok(getDemoCustomAnalysis(request));
        }
        
        com.stockman.model.AnalysisResponse response = stockAnalyzer.customAnalysis(request, session.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<com.stockman.model.AgentInfo>> getAvailableAgents() {
        List<com.stockman.model.AgentInfo> agents = List.of(
                com.stockman.model.AgentInfo.builder()
                        .type("FUNDAMENTAL")
                        .name("Fundamental Analyst")
                        .description("Expert in company financials, valuation, and long-term value investing")
                        .icon("📊")
                        .focusAreas(new String[]{"P/E ratios", "Revenue growth", "Profit margins", "Competitive advantages"})
                        .build(),
                com.stockman.model.AgentInfo.builder()
                        .type("TECHNICAL")
                        .name("Technical Analyst")
                        .description("Specialist in chart patterns, price action, and momentum indicators")
                        .icon("📈")
                        .focusAreas(new String[]{"Support/Resistance", "Moving averages", "RSI", "Chart patterns"})
                        .build(),
                com.stockman.model.AgentInfo.builder()
                        .type("QUANTITATIVE")
                        .name("Quantitative Analyst")
                        .description("Data scientist using statistical models and risk metrics")
                        .icon("🔢")
                        .focusAreas(new String[]{"Volatility", "Beta", "Sharpe ratio", "Statistical analysis"})
                        .build(),
                com.stockman.model.AgentInfo.builder()
                        .type("SENTIMENT")
                        .name("Sentiment Analyst")
                        .description("Tracker of market psychology, news, and investor behavior")
                        .icon("💭")
                        .focusAreas(new String[]{"News analysis", "Social media", "Analyst ratings", "Market sentiment"})
                        .build(),
                com.stockman.model.AgentInfo.builder()
                        .type("GENERAL")
                        .name("General AI Analyst")
                        .description("Comprehensive multi-faceted analysis across all dimensions")
                        .icon("🤖")
                        .focusAreas(new String[]{"Holistic view", "Multi-faceted", "Balanced approach", "Custom queries"})
                        .build()
        );
        return ResponseEntity.ok(agents);
    }

    private com.stockman.model.AnalysisResponse getDemoCustomAnalysis(com.stockman.model.AnalysisRequest request) {
        String answer = switch (request.getAgentType()) {
            case FUNDAMENTAL -> "From a fundamental perspective, " + (request.getSymbol() != null ? request.getSymbol() : "this stock") + 
                " shows strong financial health with improving margins and consistent revenue growth. The P/E ratio is reasonable compared to industry peers.";
            case TECHNICAL -> "Technically, " + (request.getSymbol() != null ? request.getSymbol() : "this stock") + 
                " is trading above its key moving averages, showing bullish momentum. RSI is at 62, indicating room for further upside before reaching overbought territory.";
            case QUANTITATIVE -> "Statistical analysis reveals " + (request.getSymbol() != null ? request.getSymbol() : "this stock") + 
                " has a beta of 1.2, indicating higher volatility than the market. Sharpe ratio of 0.85 suggests reasonable risk-adjusted returns.";
            case SENTIMENT -> "Market sentiment for " + (request.getSymbol() != null ? request.getSymbol() : "this stock") + 
                " is moderately bullish. Recent analyst upgrades and positive news flow have improved investor confidence.";
            case GENERAL -> "Based on comprehensive analysis, " + (request.getSymbol() != null ? request.getSymbol() : "this stock") + 
                " presents a balanced investment opportunity with good fundamentals, positive technical setup, and favorable sentiment.";
        };
        
        return com.stockman.model.AnalysisResponse.builder()
                .answer(answer)
                .confidence(0.80)
                .sources(List.of("Portfolio Data", "AI Analysis", "Market Data"))
                .followUpQuestions(List.of(
                        com.stockman.prompts.AgentPrompts.getSuggestedQuestions(request.getAgentType())
                ))
                .agentType(request.getAgentType().name())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private boolean isDemoMode(HttpSession session) {
        Boolean demoMode = (Boolean) session.getAttribute("demoMode");
        return demoMode != null && demoMode;
    }

    private StockInsight.AnalysisType parseAnalysisType(String type) {
        try {
            return StockInsight.AnalysisType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return StockInsight.AnalysisType.COMPREHENSIVE;
        }
    }

    // Demo responses for testing
    private StockInsight getDemoStockInsight(String symbol, StockInsight.AnalysisType type) {
        return StockInsight.builder()
                .symbol(symbol)
                .companyName(symbol + " Limited")
                .analysisDate(LocalDateTime.now())
                .analysisType(type)
                .overallRating("BUY")
                .confidenceScore(75)
                .summary("Based on comprehensive analysis, " + symbol + " shows strong fundamentals with moderate growth potential. The stock has maintained consistent revenue growth and has a healthy balance sheet.")
                .fundamentalAnalysis("The company shows strong fundamentals with a P/E ratio of 22.5, which is reasonable for the sector. Revenue has grown at 15% CAGR over the past 5 years. Debt-to-equity ratio of 0.3 indicates conservative financial management. ROE of 18% demonstrates efficient capital utilization.")
                .technicalAnalysis("The stock is currently trading above its 50-day and 200-day moving averages, indicating bullish momentum. RSI at 58 suggests the stock is neither overbought nor oversold. Support levels are at ₹2,400 and ₹2,250, with resistance at ₹2,800.")
                .riskAssessment("Key risks include sector-wide regulatory changes, currency fluctuation exposure due to international operations, and competitive pressure from new market entrants. Market volatility remains a concern given current global uncertainties.")
                .growthPotential("Strong growth potential driven by digital transformation initiatives, expansion into tier-2 cities, and new product launches. Management guidance suggests 12-15% revenue growth for the next fiscal year.")
                .keyStrengths(List.of(
                        "Market leader in core business segments",
                        "Strong brand recognition and customer loyalty",
                        "Diversified revenue streams",
                        "Experienced management team",
                        "Healthy cash flow generation"
                ))
                .keyRisks(List.of(
                        "Regulatory uncertainty in key markets",
                        "Increasing competition from smaller players",
                        "Commodity price volatility",
                        "Currency exchange rate fluctuations"
                ))
                .recommendations(List.of(
                        "Consider adding to position on dips below ₹2,450",
                        "Set stop-loss at ₹2,200 for risk management",
                        "Hold for long-term wealth creation",
                        "Review position quarterly based on earnings"
                ))
                .shortTermOutlook("Neutral to slightly bullish for the next 1-3 months. Watch for Q4 results which could be a catalyst.")
                .mediumTermOutlook("Positive outlook for 6-12 months as new initiatives start contributing to revenue.")
                .longTermOutlook("Strong buy for 3-5 year horizon. Company is well-positioned to benefit from India's growth story.")
                .build();
    }

    private InvestmentStrategy getDemoPortfolioAnalysis() {
        return InvestmentStrategy.builder()
                .strategyType(InvestmentStrategy.StrategyType.BALANCED)
                .generatedAt(LocalDateTime.now())
                .summary("Your portfolio shows a healthy mix of large-cap stocks with a bias towards banking and technology sectors. Overall portfolio health is good with a 7.67% return. Consider adding more diversification in defensive sectors.")
                .portfolioHealthScore("B+")
                .diversificationScore("Moderate - Heavy concentration in Banking (32.7%) and Technology (29.5%). Consider adding FMCG and Pharma for better diversification.")
                .riskLevel("MEDIUM")
                .stocksToBuy(List.of("SUNPHARMA", "HINDUNILVR", "ASIANPAINT"))
                .stocksToSell(List.of())
                .stocksToHold(List.of("RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "TATAMOTORS"))
                .recommendations(List.of(
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("Reduce Banking Sector Exposure")
                                .description("Banking sector is at 32.7% which is slightly high. Consider reducing ICICIBANK position by 25% to bring sector weight below 28%.")
                                .priority("MEDIUM")
                                .expectedImpact("Better risk-adjusted returns")
                                .timeframe("Next 30 days")
                                .build(),
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("Add Defensive Stocks")
                                .description("Portfolio lacks exposure to defensive sectors. Add FMCG stocks like HINDUNILVR or ITC for stability during market downturns.")
                                .priority("HIGH")
                                .expectedImpact("Portfolio stability during volatility")
                                .timeframe("Next 60 days")
                                .build()
                ))
                .rebalanceActions(List.of(
                        InvestmentStrategy.RebalanceAction.builder()
                                .symbol("ICICIBANK")
                                .action("REDUCE")
                                .currentWeight("14.6%")
                                .targetWeight("10%")
                                .reason("Reduce concentration risk in banking sector")
                                .build(),
                        InvestmentStrategy.RebalanceAction.builder()
                                .symbol("HINDUNILVR")
                                .action("BUY")
                                .currentWeight("0%")
                                .targetWeight("8%")
                                .reason("Add defensive FMCG exposure")
                                .build()
                ))
                .riskMitigationSteps(List.of(
                        "Set trailing stop-loss of 15% on all positions",
                        "Maintain 10% cash reserve for opportunities",
                        "Review portfolio monthly during earnings season"
                ))
                .sectorOpportunities(List.of("Healthcare", "FMCG", "Renewable Energy"))
                .emergingTrends(List.of("Electric Vehicles", "Digital Payments", "Green Energy"))
                .build();
    }

    private InvestmentStrategy getDemoLongTermStrategy() {
        return InvestmentStrategy.builder()
                .strategyType(InvestmentStrategy.StrategyType.LONG_TERM_GROWTH)
                .generatedAt(LocalDateTime.now())
                .summary("Long-term growth strategy focused on wealth creation over 3-5 years. Focus on quality large-caps with strong moats and consistent dividend history.")
                .portfolioHealthScore("B+")
                .diversificationScore("Room for improvement in sector diversification")
                .riskLevel("MEDIUM")
                .stocksToBuy(List.of("BAJFINANCE", "TITAN", "NESTLEIND", "PIDILITIND"))
                .stocksToSell(List.of())
                .stocksToHold(List.of("RELIANCE", "TCS", "HDFCBANK", "TATAMOTORS"))
                .recommendations(List.of(
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("Core Holdings Strategy")
                                .description("Maintain RELIANCE, TCS, and HDFCBANK as core long-term holdings. These blue-chips provide stability and consistent growth.")
                                .priority("HIGH")
                                .expectedImpact("12-15% CAGR expected")
                                .timeframe("3-5 years")
                                .build(),
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("SIP in Quality Stocks")
                                .description("Start monthly SIP in BAJFINANCE and TITAN to accumulate at various price points.")
                                .priority("MEDIUM")
                                .expectedImpact("Rupee cost averaging")
                                .timeframe("Ongoing")
                                .build()
                ))
                .riskMitigationSteps(List.of(
                        "Diversify across 8-10 sectors",
                        "Limit single stock exposure to 15%",
                        "Review fundamentals quarterly"
                ))
                .sectorOpportunities(List.of("Financials", "Consumer Discretionary", "Technology"))
                .emergingTrends(List.of("India's rising middle class", "Digital transformation", "Manufacturing boom"))
                .build();
    }

    private InvestmentStrategy getDemoShortTermStrategy() {
        return InvestmentStrategy.builder()
                .strategyType(InvestmentStrategy.StrategyType.SHORT_TERM_TRADING)
                .generatedAt(LocalDateTime.now())
                .summary("Short-term trading opportunities based on technical analysis. Focus on momentum plays with defined entry/exit levels.")
                .portfolioHealthScore("N/A")
                .diversificationScore("N/A for short-term trading")
                .riskLevel("HIGH")
                .stocksToBuy(List.of("TATAMOTORS", "RELIANCE"))
                .stocksToSell(List.of("INFY"))
                .stocksToHold(List.of("TCS", "HDFCBANK", "ICICIBANK"))
                .recommendations(List.of(
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("TATAMOTORS Breakout Trade")
                                .description("Stock showing strong momentum with potential breakout above ₹900. Entry: ₹895, Target: ₹950, Stop-loss: ₹870")
                                .priority("HIGH")
                                .expectedImpact("6-8% potential upside")
                                .timeframe("1-2 weeks")
                                .build(),
                        InvestmentStrategy.StrategyRecommendation.builder()
                                .title("Book Partial Profits in INFY")
                                .description("INFY showing weakness. Consider booking 50% position on any bounce to ₹1,510.")
                                .priority("MEDIUM")
                                .expectedImpact("Preserve capital")
                                .timeframe("This week")
                                .build()
                ))
                .riskMitigationSteps(List.of(
                        "Strict stop-loss of 3-5% on all trades",
                        "Position sizing: Max 20% of capital per trade",
                        "Exit if stock closes below key support"
                ))
                .sectorOpportunities(List.of("Auto", "Energy"))
                .emergingTrends(List.of("Earnings season momentum", "FII buying in large-caps"))
                .build();
    }
}
