package com.stockman.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockman.model.Holding;
import com.stockman.model.InvestmentStrategy;
import com.stockman.model.PortfolioSummary;
import com.stockman.model.StockInsight;
import com.stockman.prompts.AnalysisPrompts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class StockAnalyzer {

    private final GeminiService geminiService;
    private final PortfolioService portfolioService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockInsight analyzeStock(String sessionId, String symbol, StockInsight.AnalysisType analysisType) {
        Holding holding = portfolioService.getHoldingBySymbol(sessionId, symbol);
        
        if (holding == null) {
            return createEmptyInsight(symbol, "Stock not found in portfolio");
        }

        String prompt = switch (analysisType) {
            case LONG_TERM -> formatLongTermPrompt(holding);
            case SHORT_TERM -> formatShortTermPrompt(holding);
            case COMPREHENSIVE -> formatLongTermPrompt(holding); // Default to long-term for comprehensive
        };

        String response = geminiService.analyzeWithPrompt(AnalysisPrompts.SYSTEM_ROLE, prompt);
        return parseStockInsight(response, symbol, analysisType);
    }

    public StockInsight analyzeStockWithData(Holding holding, StockInsight.AnalysisType analysisType) {
        String prompt = switch (analysisType) {
            case LONG_TERM -> formatLongTermPrompt(holding);
            case SHORT_TERM -> formatShortTermPrompt(holding);
            case COMPREHENSIVE -> formatLongTermPrompt(holding);
        };

        String response = geminiService.analyzeWithPrompt(AnalysisPrompts.SYSTEM_ROLE, prompt);
        return parseStockInsight(response, holding.getTradingSymbol(), analysisType);
    }

    public InvestmentStrategy generateLongTermStrategy(String sessionId, String riskTolerance, String primaryGoal) {
        PortfolioSummary summary = portfolioService.getPortfolioSummary(sessionId);
        
        if (summary.getHoldings().isEmpty()) {
            return createEmptyStrategy("No holdings found in portfolio");
        }

        String holdingsData = AnalysisPrompts.formatHoldingsForPrompt(summary.getHoldings());
        String prompt = String.format(AnalysisPrompts.LONG_TERM_STRATEGY, 
                holdingsData, riskTolerance, primaryGoal);

        String response = geminiService.analyzeWithPrompt(AnalysisPrompts.SYSTEM_ROLE, prompt);
        return parseInvestmentStrategy(response, InvestmentStrategy.StrategyType.LONG_TERM_GROWTH);
    }

    public InvestmentStrategy generateShortTermStrategy(String sessionId) {
        PortfolioSummary summary = portfolioService.getPortfolioSummary(sessionId);
        
        if (summary.getHoldings().isEmpty()) {
            return createEmptyStrategy("No holdings found in portfolio");
        }

        String holdingsData = AnalysisPrompts.formatHoldingsForPrompt(summary.getHoldings());
        String prompt = String.format(AnalysisPrompts.SHORT_TERM_STRATEGY, holdingsData);

        String response = geminiService.analyzeWithPrompt(AnalysisPrompts.SYSTEM_ROLE, prompt);
        return parseInvestmentStrategy(response, InvestmentStrategy.StrategyType.SHORT_TERM_TRADING);
    }

    public com.stockman.model.AnalysisResponse customAnalysis(com.stockman.model.AnalysisRequest request, String sessionId) {
        // Get stock data if symbol is provided
        String context = "";
        if (request.getSymbol() != null && !request.getSymbol().isEmpty()) {
            Holding holding = portfolioService.getHoldingBySymbol(sessionId, request.getSymbol());
            if (holding != null) {
                context = formatHoldingContext(holding);
            }
        }

        // Build conversation history context
        String conversationContext = com.stockman.prompts.AgentPrompts.buildConversationContext(request);
        
        // Get agent-specific system prompt
        String systemPrompt = com.stockman.prompts.AgentPrompts.getSystemPrompt(request.getAgentType());
        
        // Build the full user prompt
        String fullPrompt = String.format("%s\n\nStock Context:\n%s\n\nUser Question: %s",
                conversationContext, context, request.getPrompt());
        
        // Get AI response
        String response = geminiService.analyzeWithPrompt(systemPrompt, fullPrompt);
        
        // Generate follow-up questions
        String[] suggestedQuestions = com.stockman.prompts.AgentPrompts.getSuggestedQuestions(request.getAgentType());
        List<String> followUpQuestions = List.of(suggestedQuestions).stream()
                .limit(3)
                .toList();
        
        return com.stockman.model.AnalysisResponse.builder()
                .answer(response)
                .confidence(0.85)
                .sources(List.of("Portfolio Data", "AI Analysis"))
                .followUpQuestions(followUpQuestions)
                .agentType(request.getAgentType().name())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String formatHoldingContext(Holding holding) {
        return String.format("""
                Symbol: %s
                Current Price: ₹%.2f
                Average Buy Price: ₹%.2f
                Quantity: %d
                Total Investment: ₹%.2f
                Current Value: ₹%.2f
                P&L: ₹%.2f (%.2f%%)
                """,
                holding.getTradingSymbol(),
                holding.getLastPrice(),
                holding.getAveragePrice(),
                holding.getQuantity(),
                holding.getInvestedValue(),
                holding.getCurrentValue(),
                holding.getPnl(),
                holding.getPnlPercentage()
        );
    }

    public InvestmentStrategy analyzePortfolio(String sessionId) {
        PortfolioSummary summary = portfolioService.getPortfolioSummary(sessionId);
        
        if (summary.getHoldings().isEmpty()) {
            return createEmptyStrategy("No holdings found in portfolio");
        }

        String holdingsData = AnalysisPrompts.formatHoldingsForPrompt(summary.getHoldings());
        String prompt = String.format(AnalysisPrompts.PORTFOLIO_ANALYSIS,
                summary.getTotalInvestment(),
                summary.getCurrentValue(),
                summary.getTotalPnl(),
                summary.getTotalPnlPercentage(),
                summary.getTotalHoldings(),
                holdingsData);

        String response = geminiService.analyzeWithPrompt(AnalysisPrompts.SYSTEM_ROLE, prompt);
        return parseInvestmentStrategy(response, InvestmentStrategy.StrategyType.BALANCED);
    }

    private String formatLongTermPrompt(Holding holding) {
        return String.format(AnalysisPrompts.STOCK_ANALYSIS_LONG_TERM,
                holding.getTradingSymbol(),
                holding.getLastPrice(),
                holding.getAveragePrice(),
                holding.getQuantity(),
                holding.getPnl(),
                holding.getPnlPercentage());
    }

    private String formatShortTermPrompt(Holding holding) {
        return String.format(AnalysisPrompts.STOCK_ANALYSIS_SHORT_TERM,
                holding.getTradingSymbol(),
                holding.getLastPrice(),
                holding.getAveragePrice(),
                holding.getQuantity(),
                holding.getPnl(),
                holding.getPnlPercentage());
    }

    private StockInsight parseStockInsight(String response, String symbol, StockInsight.AnalysisType analysisType) {
        try {
            // Try to extract JSON from the response
            String jsonStr = extractJson(response);
            var jsonNode = objectMapper.readTree(jsonStr);

            return StockInsight.builder()
                    .symbol(symbol)
                    .analysisDate(LocalDateTime.now())
                    .analysisType(analysisType)
                    .overallRating(jsonNode.path("overallRating").asText("HOLD"))
                    .confidenceScore(jsonNode.path("confidenceScore").asInt(50))
                    .summary(jsonNode.path("summary").asText())
                    .fundamentalAnalysis(jsonNode.path("fundamentalAnalysis").asText())
                    .technicalAnalysis(jsonNode.path("technicalAnalysis").asText())
                    .riskAssessment(jsonNode.path("riskAssessment").asText())
                    .growthPotential(jsonNode.path("growthPotential").asText())
                    .keyStrengths(parseStringList(jsonNode.path("keyStrengths")))
                    .keyRisks(parseStringList(jsonNode.path("keyRisks")))
                    .recommendations(parseStringList(jsonNode.path("recommendations")))
                    .shortTermOutlook(jsonNode.path("shortTermOutlook").asText())
                    .mediumTermOutlook(jsonNode.path("mediumTermOutlook").asText())
                    .longTermOutlook(jsonNode.path("longTermOutlook").asText())
                    .build();
        } catch (Exception e) {
            log.error("Error parsing stock insight: {}", e.getMessage());
            return createEmptyInsight(symbol, "Error parsing analysis response");
        }
    }

    private InvestmentStrategy parseInvestmentStrategy(String response, InvestmentStrategy.StrategyType strategyType) {
        try {
            String jsonStr = extractJson(response);
            var jsonNode = objectMapper.readTree(jsonStr);

            return InvestmentStrategy.builder()
                    .strategyType(strategyType)
                    .generatedAt(LocalDateTime.now())
                    .summary(jsonNode.path("summary").asText())
                    .portfolioHealthScore(jsonNode.path("portfolioHealthScore").asText("B"))
                    .diversificationScore(jsonNode.path("diversificationScore").asText())
                    .riskLevel(jsonNode.path("riskLevel").asText("MEDIUM"))
                    .stocksToBuy(parseStringList(jsonNode.path("stocksToBuy")))
                    .stocksToSell(parseStringList(jsonNode.path("stocksToSell")))
                    .stocksToHold(parseStringList(jsonNode.path("stocksToHold")))
                    .riskMitigationSteps(parseStringList(jsonNode.path("riskMitigationSteps")))
                    .sectorOpportunities(parseStringList(jsonNode.path("sectorOpportunities")))
                    .emergingTrends(parseStringList(jsonNode.path("emergingTrends")))
                    .build();
        } catch (Exception e) {
            log.error("Error parsing investment strategy: {}", e.getMessage());
            return createEmptyStrategy("Error parsing strategy response");
        }
    }

    private String extractJson(String response) {
        // Find JSON in the response (it might be wrapped in markdown code blocks)
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    private List<String> parseStringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isArray()) {
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(com.fasterxml.jackson.databind.JsonNode::asText)
                    .toList();
        }
        return List.of();
    }

    private StockInsight createEmptyInsight(String symbol, String message) {
        return StockInsight.builder()
                .symbol(symbol)
                .analysisDate(LocalDateTime.now())
                .analysisType(StockInsight.AnalysisType.COMPREHENSIVE)
                .overallRating("HOLD")
                .confidenceScore(0)
                .summary(message)
                .keyStrengths(List.of())
                .keyRisks(List.of())
                .recommendations(List.of())
                .build();
    }

    private InvestmentStrategy createEmptyStrategy(String message) {
        return InvestmentStrategy.builder()
                .strategyType(InvestmentStrategy.StrategyType.BALANCED)
                .generatedAt(LocalDateTime.now())
                .summary(message)
                .portfolioHealthScore("N/A")
                .riskLevel("UNKNOWN")
                .stocksToBuy(List.of())
                .stocksToSell(List.of())
                .stocksToHold(List.of())
                .build();
    }
}
