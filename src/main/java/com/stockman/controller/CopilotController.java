package com.stockman.controller;

import com.stockman.model.*;
import com.stockman.model.AgentDefinition.CopilotAgentType;
import com.stockman.service.OrchestratorService;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
@Slf4j
public class CopilotController {

    private final OrchestratorService orchestratorService;

    private final ConcurrentHashMap<String, OrchestratorResponse> responseHistory = new ConcurrentHashMap<>();

    @PostMapping("/ask")
    public ResponseEntity<OrchestratorResponse> ask(
            @Valid @RequestBody CopilotRequest request,
            HttpSession session) {

        log.info("Copilot ask: query='{}', symbol='{}'", request.getQuery(), request.getSymbol());

        boolean isDemoMode = isDemoMode(session);
        String sessionId = session.getId();

        OrchestratorResponse response = orchestratorService.process(request, sessionId, isDemoMode);
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/analyze/{symbol}")
    public ResponseEntity<OrchestratorResponse> analyzeStock(
            @PathVariable String symbol,
            HttpSession session) {

        log.info("Copilot full analysis for: {}", symbol);

        CopilotRequest request = CopilotRequest.builder()
                .query("Provide a full comprehensive analysis of " + symbol)
                .symbol(symbol)
                .build();

        boolean isDemoMode = isDemoMode(session);
        OrchestratorResponse response = orchestratorService.process(request, session.getId(), isDemoMode);
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/portfolio-review")
    public ResponseEntity<OrchestratorResponse> portfolioReview(HttpSession session) {
        log.info("Copilot portfolio review");

        CopilotRequest request = CopilotRequest.builder()
                .query("Review my entire portfolio. Assess diversification, risk exposure, and suggest rebalancing actions.")
                .build();

        boolean isDemoMode = isDemoMode(session);
        OrchestratorResponse response = orchestratorService.process(request, session.getId(), isDemoMode);
        responseHistory.put(response.getRequestId(), response);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/reasoning/{requestId}")
    public ResponseEntity<OrchestratorResponse> getReasoningChain(@PathVariable String requestId) {
        OrchestratorResponse response = responseHistory.get(requestId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/agents")
    public ResponseEntity<List<AgentDefinition>> getAvailableAgents() {
        List<AgentDefinition> agents = List.of(
                buildAgent(CopilotAgentType.FUNDAMENTAL, "Fundamental Analyst",
                        "Expert in company financials, valuation, and long-term value investing",
                        "chart-bar", "gemini",
                        List.of("P/E ratios", "Revenue growth", "Profit margins", "Competitive advantages"),
                        List.of("What are the key financial metrics?", "How does valuation compare to peers?")),
                buildAgent(CopilotAgentType.TECHNICAL, "Technical Analyst",
                        "Specialist in chart patterns, price action, and momentum indicators",
                        "chart-line", "gemini",
                        List.of("Support/Resistance", "Moving averages", "RSI", "Chart patterns"),
                        List.of("What are key support and resistance levels?", "What's the current trend?")),
                buildAgent(CopilotAgentType.QUANTITATIVE, "Quantitative Analyst",
                        "Data scientist using statistical models and risk metrics",
                        "calculator", "gemini",
                        List.of("Volatility", "Beta", "Sharpe ratio", "Statistical analysis"),
                        List.of("What is the volatility profile?", "What are risk-adjusted returns?")),
                buildAgent(CopilotAgentType.SENTIMENT, "Sentiment Analyst",
                        "Tracker of market psychology, news, and investor behavior",
                        "users", "gemini",
                        List.of("News analysis", "Social media", "Analyst ratings", "Market sentiment"),
                        List.of("What's the current market sentiment?", "How is analyst coverage trending?")),
                buildAgent(CopilotAgentType.RISK_ASSESSOR, "Risk Assessor",
                        "Risk management specialist evaluating downside scenarios and volatility",
                        "shield", "openrouter",
                        List.of("Downside risk", "Tail risk", "Volatility regimes", "Correlation breakdown"),
                        List.of("What are the top 3 risks?", "What's the worst-case scenario?")),
                buildAgent(CopilotAgentType.PORTFOLIO_OPTIMIZER, "Portfolio Optimizer",
                        "Allocation specialist for portfolio rebalancing and diversification",
                        "sliders", "gemini",
                        List.of("Sector allocation", "Position sizing", "Rebalancing", "Diversification"),
                        List.of("How should I rebalance?", "Is my portfolio diversified enough?")),
                buildAgent(CopilotAgentType.GEOPOLITICAL, "Geopolitical Analyst",
                        "Assesses impact of wars, tariffs, and global events on your portfolio",
                        "globe", "openrouter",
                        List.of("Trade policy", "Sanctions", "Currency impact", "Geopolitical risk"),
                        List.of("How are tariffs affecting my portfolio?", "What global risks should I watch?")),
                buildAgent(CopilotAgentType.TRADE_EXECUTOR, "Trade Executor",
                        "Creates actionable trade plans with entry, exit, and stop-loss levels",
                        "target", "openrouter",
                        List.of("Entry/Exit points", "Stop-loss", "Position sizing", "Risk/Reward"),
                        List.of("Give me a trade plan for this stock", "Where should I set my stop-loss?")),
                buildAgent(CopilotAgentType.GENERAL, "General Analyst",
                        "Comprehensive multi-faceted analysis across all dimensions",
                        "brain", "gemini",
                        List.of("Holistic view", "Multi-faceted", "Balanced approach", "Custom queries"),
                        List.of("Should I buy, hold, or sell?", "How does this fit in my portfolio?"))
        );
        return ResponseEntity.ok(agents);
    }

    private AgentDefinition buildAgent(CopilotAgentType type, String name, String description,
                                        String icon, String preferredModel,
                                        List<String> focusAreas, List<String> suggestedQuestions) {
        return AgentDefinition.builder()
                .type(type)
                .name(name)
                .description(description)
                .icon(icon)
                .preferredModel(preferredModel)
                .fallbackModel(preferredModel.equals("openrouter") ? "gemini" : "openrouter")
                .focusAreas(focusAreas)
                .suggestedQuestions(suggestedQuestions)
                .build();
    }

    private boolean isDemoMode(HttpSession session) {
        Boolean demoMode = (Boolean) session.getAttribute("demoMode");
        return demoMode != null && demoMode;
    }
}
