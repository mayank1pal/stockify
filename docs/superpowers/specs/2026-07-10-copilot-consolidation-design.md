# Copilot Consolidation — Design

## Problem

The frontend rewrite currently in progress (uncommitted on `main`) gave the app a clean, consistent visual design across 5 pages (Dashboard, Holdings, Analysis, Strategy, Copilot), but the page count itself is still a source of complexity: two of those pages (Analysis, Strategy) are thin frontends over the legacy single-agent backend (`AnalysisController`/`StockAnalyzer`), and both overlap heavily with what the newer multi-agent Copilot (`CopilotController`/`OrchestratorService`) can already do via free-text queries.

Goal: reduce the nav from 5 pages to 3 (Dashboard, Holdings, Copilot) by folding Analysis and Strategy's use cases into Copilot, without touching any backend code.

## Scope

**Frontend-only.** The legacy backend (`AnalysisController`, `StockAnalyzer`, `AgentPrompts`/`AnalysisPrompts`, `StockInsight`/`InvestmentStrategy` DTOs) is left in place, untouched, for a possible future cleanup once the new UI is proven out. This change only removes what calls into it from the frontend.

## Files

### Delete
- `src/main/resources/static/analysis.html`
- `src/main/resources/static/strategy.html`
- `src/main/resources/static/scripts/app.js`

`app.js` is already fully dead code independent of this change: `dashboard.html` and `holdings.html` define all their own render logic inline and never call into it, and `copilot.js` (loaded after it) redeclares `showLoading`/`showError` as its own global functions, which silently shadow `app.js`'s versions (last `<script>` tag's function declaration wins). The only functions in `app.js` that are still reachable at all — `renderStockInsight` (used by `analysis.html`) and `renderStrategy` (used by `strategy.html`) — go away once those two pages are deleted.

### Modify

**`dashboard.html`, `holdings.html`, `copilot.html`**
- Nav: remove the Analysis and Strategy `<li>` links. Nav becomes Dashboard → Holdings → Copilot (3 items, same relative order as today).
- Remove the `<script src="scripts/app.js"></script>` include.

**`dashboard.html`** additionally — retarget the 4 existing Quick Action links (count unchanged, just new hrefs). These are raw HTML `<a href="...">` tags, so the `&` between query params must be written as `&amp;` in the markup:
- "Analyze a Stock": `analysis.html` → `copilot.html` (plain link; this action was never tied to a specific symbol, so no query params needed — the user picks a stock from Copilot's own dropdown)
- "Long-Term Strategy": `strategy.html?type=long` → `copilot.html?query=Generate+a+long-term+wealth-creation+investment+strategy+for+my+portfolio+with+moderate+risk+tolerance&amp;autoSend=1`
- "Short-Term Strategy": `strategy.html?type=short` → `copilot.html?query=Give+me+short-term+trading+opportunities+with+entry%2C+exit%2C+and+stop-loss+levels+for+my+current+holdings&amp;autoSend=1`
- "Ask Copilot": unchanged (`copilot.html`)

**`holdings.html`** additionally — the per-row "Analyze" button and row-click handler currently navigate to `analysis.html?symbol=<SYMBOL>`. Both call sites are JS (`window.location.href = ...`), built via string concatenation, not raw HTML hrefs — so no entity-escaping concern there. Both change to:
`copilot.html?symbol=<SYMBOL>&query=Give+me+a+full+comprehensive+analysis+of+<SYMBOL>&autoSend=1`

**`copilot.html`** additionally — add two more static suggestion chips to the existing 5, so the Strategy-style use cases are discoverable without needing URL params at all:
- "Give me a long-term growth strategy for my portfolio"
- "What are good short-term trading opportunities right now?"

**`scripts/copilot.js`** — one functional change, in `init()`:
- Read `symbol`, `query`, `autoSend` from `new URLSearchParams(window.location.search)`.
- After `loadHoldings()` populates the stock dropdown: if `symbol` is present and matches one of the loaded holdings' `tradingSymbol`, set it as the dropdown's selected value. If it doesn't match, leave the dropdown on "No specific stock" — no error shown.
- If `query` is present, set it as the textarea's value.
- If `autoSend === '1'` and `query` is present, call `sendQuery()` automatically. If `autoSend` is set without a `query`, do nothing (consistent with `sendQuery()`'s existing empty-input guard).

**`scripts/api.js`** — remove the functions that become unreachable once `analysis.html`/`strategy.html`/`app.js` are gone: `analyzeStock`, `analyzePortfolio`, `getLongTermStrategy`, `getShortTermStrategy`, `getAgents` (the legacy `/api/analysis/agents` one — `copilotGetAgents` is a separate function and stays), `customAnalysis`, `getRatingClass`, `getRatingDisplay`. Everything else in `api.js` stays, including `formatCurrency`/`formatPercentage`, which `holdings.html` actively depends on via its `typeof === 'undefined'` guard.

**`styles/main.css`** — prune CSS rules that become orphaned by the above deletions (e.g. analysis-grid/analysis-section, rating-* classes used only by the deleted `renderStockInsight`). Each rule must be grep-verified as unused elsewhere before removal — some class names (e.g. `badge-buy`/`badge-hold`/`badge-sell`) are shared with other still-live pages and must stay.

## Behavioral mapping (old → new)

| Old entry point | New entry point | Intent-classifier match |
|---|---|---|
| Holdings row "Analyze" | Copilot, symbol pre-filled, auto-sent | "comprehensive" → FUNDAMENTAL+TECHNICAL+QUANTITATIVE+RISK_ASSESSOR+SENTIMENT (closest match to the old multi-section `StockInsight`) |
| Dashboard "Long-Term Strategy" | Copilot, preset query, auto-sent | "portfolio" → PORTFOLIO_OPTIMIZER+RISK_ASSESSOR+TRADE_EXECUTOR |
| Dashboard "Short-Term Strategy" | Copilot, preset query, auto-sent | "entry/exit/stop-loss" → TRADE_EXECUTOR |
| Dashboard "Analyze a Stock" | Copilot, no pre-fill | user picks stock + writes/picks a question |
| Analysis page's single-agent picker | Copilot suggestion chips / free-text phrasing | no exact equivalent — accepted trade-off |
| Strategy page's risk-tolerance/goal dropdowns | Copilot preset queries (fixed phrasing, not user-adjustable per-request) | no exact equivalent — accepted trade-off |

This is an approximation, not exact feature parity. The explicit structured inputs (single-agent picker, risk/goal dropdowns) are replaced by phrasing that happens to route through `IntentClassifier`'s keyword buckets to a similar agent panel. That's the trade-off this design accepts in exchange for one simpler interaction model.

## Error handling

No new error paths. `symbol` param mismatches fail silently to "No specific stock" (not an error state — mirrors how the dropdown already behaves for a manually-cleared selection). All API failures continue to go through `copilot.js`'s existing `showError()`.

## Testing / verification

No backend change, so no new unit tests. Verification is manual, via the same Playwright-driven walkthrough used earlier in this project:
1. Confirm nav is 3 items (Dashboard, Holdings, Copilot) on every remaining page.
2. Confirm `analysis.html` and `strategy.html` 404.
3. From Dashboard, click each of the 4 Quick Actions; confirm each lands on Copilot with the expected pre-fill/auto-send behavior and a rendered response for the two auto-sent ones.
4. From Holdings, click a row's "Analyze" button and click a row itself; confirm both produce the same Copilot hand-off.
5. Check browser console for errors on all three remaining pages (catches any stray reference to a removed `app.js` function).
