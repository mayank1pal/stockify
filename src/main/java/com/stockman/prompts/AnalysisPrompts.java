package com.stockman.prompts;

public class AnalysisPrompts {

    public static final String SYSTEM_ROLE = """
        You are an expert financial analyst with deep knowledge of the Indian stock market, 
        technical analysis, fundamental analysis, and investment strategies. You provide 
        clear, actionable insights while maintaining a balanced perspective on risks and 
        opportunities. Always base your analysis on the data provided and clearly state 
        any assumptions. Never provide specific price targets as financial advice.
        """;

    public static final String STOCK_ANALYSIS_LONG_TERM = """
        Analyze the following stock for long-term investment potential (1-5 years horizon):
        
        Stock: %s
        Current Price: ₹%s
        Average Buy Price: ₹%s
        Quantity Held: %d
        Current P&L: ₹%s (%s%%)
        
        Please provide a comprehensive analysis covering:
        
        1. **Fundamental Analysis**: Evaluate the company's financial health, revenue growth, 
           profitability, debt levels, and competitive position.
        
        2. **Growth Potential**: Assess future growth prospects, market expansion opportunities, 
           and industry tailwinds/headwinds.
        
        3. **Risk Assessment**: Identify key risks including market, sector, company-specific, 
           and regulatory risks.
        
        4. **Long-Term Outlook**: Provide perspective on where this stock could be in 3-5 years.
        
        5. **Recommendation**: Suggest whether to HOLD, ADD MORE, REDUCE, or EXIT the position.
        
        Format your response as JSON with the following structure:
        {
            "overallRating": "STRONG_BUY|BUY|HOLD|SELL|STRONG_SELL",
            "confidenceScore": 1-100,
            "summary": "Brief summary",
            "fundamentalAnalysis": "Detailed fundamental analysis",
            "technicalAnalysis": "Current technical setup",
            "riskAssessment": "Risk analysis",
            "growthPotential": "Growth outlook",
            "keyStrengths": ["strength1", "strength2"],
            "keyRisks": ["risk1", "risk2"],
            "recommendations": ["action1", "action2"],
            "shortTermOutlook": "1-3 months outlook",
            "mediumTermOutlook": "3-12 months outlook",
            "longTermOutlook": "1-5 years outlook"
        }
        """;

    public static final String STOCK_ANALYSIS_SHORT_TERM = """
        Analyze the following stock for short-term trading opportunities (days to weeks):
        
        Stock: %s
        Current Price: ₹%s
        Average Buy Price: ₹%s
        Quantity Held: %d
        Current P&L: ₹%s (%s%%)
        
        Please provide a technical analysis covering:
        
        1. **Technical Indicators**: Analyze price action, moving averages, RSI, MACD, 
           and volume patterns.
        
        2. **Support & Resistance**: Identify key price levels and potential breakout points.
        
        3. **Momentum Analysis**: Evaluate current momentum and trend strength.
        
        4. **Entry/Exit Points**: Suggest optimal entry, exit, and stop-loss levels.
        
        5. **Short-Term Outlook**: Provide a 1-4 week price outlook.
        
        Format your response as JSON with the following structure:
        {
            "overallRating": "STRONG_BUY|BUY|HOLD|SELL|STRONG_SELL",
            "confidenceScore": 1-100,
            "summary": "Brief summary",
            "fundamentalAnalysis": "Brief fundamental context",
            "technicalAnalysis": "Detailed technical analysis",
            "riskAssessment": "Short-term risks",
            "growthPotential": "Short-term upside potential",
            "keyStrengths": ["strength1", "strength2"],
            "keyRisks": ["risk1", "risk2"],
            "recommendations": ["action1", "action2"],
            "shortTermOutlook": "1-4 weeks outlook with entry/exit levels",
            "mediumTermOutlook": "1-3 months context",
            "longTermOutlook": "N/A for short-term analysis"
        }
        """;

    public static final String PORTFOLIO_ANALYSIS = """
        Analyze the following investment portfolio and provide strategic recommendations:
        
        Portfolio Summary:
        - Total Investment: ₹%s
        - Current Value: ₹%s
        - Total P&L: ₹%s (%s%%)
        - Number of Holdings: %d
        
        Holdings:
        %s
        
        Please provide a comprehensive portfolio analysis covering:
        
        1. **Portfolio Health Score**: Rate the overall portfolio health (A to F).
        
        2. **Diversification Analysis**: Evaluate sector allocation and concentration risk.
        
        3. **Risk Assessment**: Analyze portfolio volatility and downside risk.
        
        4. **Rebalancing Recommendations**: Suggest which positions to increase/decrease.
        
        5. **Strategic Recommendations**: Provide actionable steps to optimize the portfolio.
        
        Format your response as JSON:
        {
            "portfolioHealthScore": "A|B|C|D|F",
            "diversificationScore": "description",
            "riskLevel": "LOW|MEDIUM|HIGH",
            "summary": "Overall assessment",
            "stocksToBuy": ["SYMBOL1", "SYMBOL2"],
            "stocksToSell": ["SYMBOL3"],
            "stocksToHold": ["SYMBOL4", "SYMBOL5"],
            "recommendations": [
                {"title": "...", "description": "...", "priority": "HIGH|MEDIUM|LOW", "expectedImpact": "...", "timeframe": "..."}
            ],
            "rebalanceActions": [
                {"symbol": "...", "action": "BUY|SELL|REDUCE|INCREASE", "currentWeight": "...", "targetWeight": "...", "reason": "..."}
            ],
            "riskMitigationSteps": ["step1", "step2"],
            "sectorOpportunities": ["sector1", "sector2"],
            "emergingTrends": ["trend1", "trend2"]
        }
        """;

    public static final String LONG_TERM_STRATEGY = """
        Based on the portfolio data provided, create a long-term investment strategy 
        (3-5 year horizon) optimized for wealth creation:
        
        Portfolio:
        %s
        
        Investment Goals:
        - Time Horizon: 3-5 years
        - Risk Tolerance: %s
        - Primary Goal: %s
        
        Please provide a comprehensive long-term strategy covering:
        
        1. **Core Holdings Strategy**: Which stocks to maintain as long-term core holdings.
        
        2. **Accumulation Plan**: Stocks to systematically accumulate over time.
        
        3. **Exit Strategy**: When and how to book profits.
        
        4. **Sector Allocation**: Recommended sector distribution for the portfolio.
        
        5. **Risk Management**: Long-term hedging and protection strategies.
        
        Provide response in the standard strategy JSON format.
        """;

    public static final String SHORT_TERM_STRATEGY = """
        Based on the portfolio data provided, create a short-term trading strategy 
        (1-4 weeks horizon):
        
        Portfolio:
        %s
        
        Please provide a short-term trading strategy covering:
        
        1. **Active Trading Candidates**: Stocks with favorable technical setups.
        
        2. **Entry/Exit Levels**: Specific price levels for trades.
        
        3. **Stop-Loss Strategy**: Risk management levels for each trade.
        
        4. **Position Sizing**: How to allocate capital across trades.
        
        5. **Weekly Action Plan**: Specific actions for the next 1-2 weeks.
        
        Provide response in the standard strategy JSON format.
        """;

    // Helper method to format holdings for prompts
    public static String formatHoldingsForPrompt(java.util.List<com.stockman.model.Holding> holdings) {
        StringBuilder sb = new StringBuilder();
        for (var holding : holdings) {
            sb.append(String.format("- %s: %d shares @ ₹%s, Current: ₹%s, P&L: ₹%s (%s%%)\n",
                    holding.getTradingSymbol(),
                    holding.getQuantity(),
                    holding.getAveragePrice(),
                    holding.getLastPrice(),
                    holding.getPnl(),
                    holding.getPnlPercentage()));
        }
        return sb.toString();
    }
}
