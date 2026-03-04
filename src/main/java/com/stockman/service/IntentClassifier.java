package com.stockman.service;

import com.stockman.model.AgentDefinition.CopilotAgentType;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class IntentClassifier {

    public List<CopilotAgentType> classifyIntent(String query, String symbol) {
        String q = query.toLowerCase();

        if (q.contains("full analysis") || q.contains("comprehensive") || q.contains("analyze everything")) {
            return List.of(
                    CopilotAgentType.FUNDAMENTAL,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.QUANTITATIVE,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.SENTIMENT
            );
        }

        if (matchesAny(q, "should i buy", "should i sell", "buy or sell", "worth buying", "good investment")) {
            return List.of(
                    CopilotAgentType.FUNDAMENTAL,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TRADE_EXECUTOR
            );
        }

        if (matchesAny(q, "tariff", "war", "geopolit", "sanction", "trade policy", "election",
                "global", "macro", "inflation", "interest rate", "rbi", "fed")) {
            return List.of(
                    CopilotAgentType.GEOPOLITICAL,
                    CopilotAgentType.PORTFOLIO_OPTIMIZER
            );
        }

        if (matchesAny(q, "risk", "downside", "worst case", "danger", "volatile", "crash")) {
            return List.of(
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TECHNICAL,
                    CopilotAgentType.QUANTITATIVE
            );
        }

        if (matchesAny(q, "rebalance", "portfolio", "allocat", "diversif", "optimize")) {
            return List.of(
                    CopilotAgentType.PORTFOLIO_OPTIMIZER,
                    CopilotAgentType.RISK_ASSESSOR,
                    CopilotAgentType.TRADE_EXECUTOR
            );
        }

        if (matchesAny(q, "support", "resistance", "chart", "pattern", "moving average",
                "rsi", "macd", "trend", "breakout")) {
            return List.of(CopilotAgentType.TECHNICAL);
        }

        if (matchesAny(q, "fundamental", "valuation", "earning", "revenue", "p/e", "balance sheet",
                "financial", "growth")) {
            return List.of(CopilotAgentType.FUNDAMENTAL);
        }

        if (matchesAny(q, "sentiment", "news", "analyst", "insider", "fii", "dii", "mood")) {
            return List.of(CopilotAgentType.SENTIMENT);
        }

        if (matchesAny(q, "entry", "exit", "stop loss", "target", "trade plan", "position size")) {
            return List.of(CopilotAgentType.TRADE_EXECUTOR);
        }

        log.info("No specific intent matched for query: '{}', using GENERAL agent", query);
        return List.of(CopilotAgentType.GENERAL);
    }

    public boolean shouldSynthesize(List<CopilotAgentType> agents) {
        return agents.size() >= 2;
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
