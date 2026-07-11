# Copilot Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the 5-page nav (Dashboard, Holdings, Analysis, Strategy, Copilot) down to 3 (Dashboard, Holdings, Copilot) by routing the legacy Analysis/Strategy use cases through the existing multi-agent Copilot page, without touching any backend code.

**Architecture:** Frontend-only change against the static resources in `src/main/resources/static/`. `analysis.html`/`strategy.html` are deleted; `copilot.js` gains URL-param handling (`symbol`/`query`/`autoSend`) so other pages can hand off into it pre-filled; `dashboard.html`/`holdings.html` retarget their links accordingly; the already-dead `scripts/app.js` and the now-orphaned legacy functions in `scripts/api.js` and `styles/main.css` are removed.

**Tech Stack:** Vanilla JS, static HTML/CSS served by Spring Boot. No build step, no frontend test runner — verification is via `grep` (confirming no dangling references) and a manual Playwright walkthrough in the final task.

---

## Task 1: Copilot URL param handling + new suggestion chips

**Files:**
- Modify: `src/main/resources/static/scripts/copilot.js:17-41` (the `init()` function)
- Modify: `src/main/resources/static/copilot.html:348-354` (suggestion chips block)

- [ ] **Step 1: Add query-param reading to `copilot.js`'s `init()`**

Replace the current `init()` function:

```javascript
async function init() {
    var authStatus = await API.getAuthStatus();
    if (!authStatus.authenticated) {
        window.location.href = '/';
        return;
    }

    document.getElementById('user-name').textContent = authStatus.userName || 'Demo User';
    document.getElementById('user-initial').textContent = (authStatus.userName || 'D')[0].toUpperCase();
    document.getElementById('logout-btn').addEventListener('click', async function () {
        await API.logout();
        window.location.href = '/';
    });

    setActiveNav();
    await loadHoldings();

    // Handle Enter key (Shift+Enter for new line)
    document.getElementById('copilotInput').addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendQuery();
        }
    });
}
```

with:

```javascript
async function init() {
    var authStatus = await API.getAuthStatus();
    if (!authStatus.authenticated) {
        window.location.href = '/';
        return;
    }

    document.getElementById('user-name').textContent = authStatus.userName || 'Demo User';
    document.getElementById('user-initial').textContent = (authStatus.userName || 'D')[0].toUpperCase();
    document.getElementById('logout-btn').addEventListener('click', async function () {
        await API.logout();
        window.location.href = '/';
    });

    setActiveNav();
    await loadHoldings();
    applyUrlParams();

    // Handle Enter key (Shift+Enter for new line)
    document.getElementById('copilotInput').addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendQuery();
        }
    });
}

// Reads ?symbol=&query=&autoSend=1 so other pages can hand off into Copilot pre-filled.
// Must run after loadHoldings() so the symbol dropdown is already populated.
function applyUrlParams() {
    var params = new URLSearchParams(window.location.search);
    var symbol = params.get('symbol');
    var query = params.get('query');
    var autoSend = params.get('autoSend') === '1';

    if (symbol) {
        var select = document.getElementById('symbolSelect');
        var matched = Array.prototype.some.call(select.options, function (opt) {
            return opt.value === symbol;
        });
        if (matched) {
            select.value = symbol;
        }
    }

    if (query) {
        document.getElementById('copilotInput').value = query;
    }

    if (autoSend && query) {
        sendQuery();
    }
}
```

- [ ] **Step 2: Add two new suggestion chips to `copilot.html`**

Replace:

```html
    <div class="suggestion-chips mb-lg animate-in animate-in-delay-1" id="suggestionChips">
      <button class="suggestion-chip" onclick="useSuggestion(this)">Should I rebalance my portfolio?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">What are the biggest risks in my holdings?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Which stocks should I add more of?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Analyze market conditions for my sector exposure</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Give me a 30-day action plan</button>
    </div>
```

with:

```html
    <div class="suggestion-chips mb-lg animate-in animate-in-delay-1" id="suggestionChips">
      <button class="suggestion-chip" onclick="useSuggestion(this)">Should I rebalance my portfolio?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">What are the biggest risks in my holdings?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Which stocks should I add more of?</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Analyze market conditions for my sector exposure</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Give me a 30-day action plan</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">Give me a long-term growth strategy for my portfolio</button>
      <button class="suggestion-chip" onclick="useSuggestion(this)">What are good short-term trading opportunities right now?</button>
    </div>
```

- [ ] **Step 3: Verify the app still starts and the page loads**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/copilot.html` (server must already be running per the project's `run` skill; if not, start it first with `./mvnw spring-boot:run` after sourcing `.env`).
Expected: `200`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/scripts/copilot.js src/main/resources/static/copilot.html
git commit -m "feat(copilot): read symbol/query/autoSend URL params, add strategy-style suggestion chips"
```

---

## Task 2: Retarget Dashboard Quick Actions

**Files:**
- Modify: `src/main/resources/static/dashboard.html:62-65`

- [ ] **Step 1: Replace the Quick Actions links**

Replace:

```html
        <a href="analysis.html" class="quick-action"><span class="action-icon">◈</span> Analyze a Stock</a>
        <a href="strategy.html?type=long" class="quick-action"><span class="action-icon">↗</span> Long-Term Strategy</a>
        <a href="strategy.html?type=short" class="quick-action"><span class="action-icon">⚡</span> Short-Term Strategy</a>
        <a href="copilot.html" class="quick-action"><span class="action-icon">⬡</span> Ask Copilot</a>
```

with:

```html
        <a href="copilot.html" class="quick-action"><span class="action-icon">◈</span> Analyze a Stock</a>
        <a href="copilot.html?query=Generate+a+long-term+wealth-creation+investment+strategy+for+my+portfolio+with+moderate+risk+tolerance&amp;autoSend=1" class="quick-action"><span class="action-icon">↗</span> Long-Term Strategy</a>
        <a href="copilot.html?query=Give+me+short-term+trading+opportunities+with+entry%2C+exit%2C+and+stop-loss+levels+for+my+current+holdings&amp;autoSend=1" class="quick-action"><span class="action-icon">⚡</span> Short-Term Strategy</a>
        <a href="copilot.html" class="quick-action"><span class="action-icon">⬡</span> Ask Copilot</a>
```

- [ ] **Step 2: Verify the hrefs decode correctly**

Run: `grep -o 'href="copilot.html[^"]*"' src/main/resources/static/dashboard.html`
Expected: four lines, the 2nd and 3rd containing `&amp;autoSend=1`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/dashboard.html
git commit -m "feat(dashboard): route Long/Short-Term Strategy quick actions through Copilot"
```

---

## Task 3: Retarget Holdings row-click and Analyze button

**Files:**
- Modify: `src/main/resources/static/holdings.html:174-204`

- [ ] **Step 1: Replace both `analysis.html` navigations with Copilot hand-offs**

Replace:

```javascript
        var tr = document.createElement('tr');
        tr.style.cursor = 'pointer';
        tr.addEventListener('click', function(event) {
          // Don't navigate if the click was on the button
          if (event.target.tagName === 'BUTTON') return;
          window.location.href = 'analysis.html?symbol=' + encodeURIComponent(symbol);
        });
```

with:

```javascript
        var tr = document.createElement('tr');
        tr.style.cursor = 'pointer';
        tr.addEventListener('click', function(event) {
          // Don't navigate if the click was on the button
          if (event.target.tagName === 'BUTTON') return;
          window.location.href = 'copilot.html?symbol=' + encodeURIComponent(symbol) +
            '&query=' + encodeURIComponent('Give me a full comprehensive analysis of ' + symbol) +
            '&autoSend=1';
        });
```

Replace:

```javascript
        var actionTd = document.createElement('td');
        var btn = document.createElement('button');
        btn.className = 'btn btn-ghost btn-sm';
        btn.textContent = 'Analyze';
        btn.addEventListener('click', function(e) {
          e.stopPropagation();
          window.location.href = 'analysis.html?symbol=' + encodeURIComponent(symbol);
        });
        actionTd.appendChild(btn);
        tr.appendChild(actionTd);
```

with:

```javascript
        var actionTd = document.createElement('td');
        var btn = document.createElement('button');
        btn.className = 'btn btn-ghost btn-sm';
        btn.textContent = 'Analyze';
        btn.addEventListener('click', function(e) {
          e.stopPropagation();
          window.location.href = 'copilot.html?symbol=' + encodeURIComponent(symbol) +
            '&query=' + encodeURIComponent('Give me a full comprehensive analysis of ' + symbol) +
            '&autoSend=1';
        });
        actionTd.appendChild(btn);
        tr.appendChild(actionTd);
```

- [ ] **Step 2: Verify no `analysis.html` references remain in this file**

Run: `grep -n "analysis.html" src/main/resources/static/holdings.html`
Expected: no output (empty)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/holdings.html
git commit -m "feat(holdings): route row-click and Analyze button through Copilot"
```

---

## Task 4: Delete Analysis/Strategy pages and trim nav to 3 items

**Files:**
- Delete: `src/main/resources/static/analysis.html`
- Delete: `src/main/resources/static/strategy.html`
- Modify: `src/main/resources/static/dashboard.html:13-17`
- Modify: `src/main/resources/static/holdings.html:13-17`
- Modify: `src/main/resources/static/copilot.html:306-310`

- [ ] **Step 1: Confirm nothing outside these two files still links to them**

Run: `grep -rln "analysis.html\|strategy.html" src/main/resources/static/`
Expected: only `src/main/resources/static/analysis.html` and `src/main/resources/static/strategy.html` themselves (self-references, e.g. their own nav `<li>` entries) — no other file.

- [ ] **Step 2: Delete the two pages**

```bash
rm src/main/resources/static/analysis.html src/main/resources/static/strategy.html
```

- [ ] **Step 3: Trim `dashboard.html`'s nav to 3 items**

Replace:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html" class="active"><span class="nav-icon">◎</span> Dashboard</a></li>
      <li><a href="holdings.html"><span class="nav-icon">▤</span> Holdings</a></li>
      <li><a href="analysis.html"><span class="nav-icon">◈</span> Analysis</a></li>
      <li><a href="copilot.html"><span class="nav-icon">⬡</span> Copilot</a></li>
      <li><a href="strategy.html"><span class="nav-icon">◆</span> Strategy</a></li>
    </ul>
```

with:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html" class="active"><span class="nav-icon">◎</span> Dashboard</a></li>
      <li><a href="holdings.html"><span class="nav-icon">▤</span> Holdings</a></li>
      <li><a href="copilot.html"><span class="nav-icon">⬡</span> Copilot</a></li>
    </ul>
```

- [ ] **Step 4: Trim `holdings.html`'s nav to 3 items**

Replace:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html"><span class="nav-icon">&#9678;</span> Dashboard</a></li>
      <li><a href="holdings.html" class="active"><span class="nav-icon">&#9636;</span> Holdings</a></li>
      <li><a href="analysis.html"><span class="nav-icon">&#9672;</span> Analysis</a></li>
      <li><a href="copilot.html"><span class="nav-icon">&#11041;</span> Copilot</a></li>
      <li><a href="strategy.html"><span class="nav-icon">&#9670;</span> Strategy</a></li>
    </ul>
```

with:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html"><span class="nav-icon">&#9678;</span> Dashboard</a></li>
      <li><a href="holdings.html" class="active"><span class="nav-icon">&#9636;</span> Holdings</a></li>
      <li><a href="copilot.html"><span class="nav-icon">&#11041;</span> Copilot</a></li>
    </ul>
```

- [ ] **Step 5: Trim `copilot.html`'s nav to 3 items**

Replace:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html"><span class="nav-icon">&#9678;</span> Dashboard</a></li>
      <li><a href="holdings.html"><span class="nav-icon">&#9828;</span> Holdings</a></li>
      <li><a href="analysis.html"><span class="nav-icon">&#9672;</span> Analysis</a></li>
      <li><a href="copilot.html" class="active"><span class="nav-icon">&#11041;</span> Copilot</a></li>
      <li><a href="strategy.html"><span class="nav-icon">&#9670;</span> Strategy</a></li>
    </ul>
```

with:

```html
    <ul class="navbar-nav" id="navMenu">
      <li><a href="dashboard.html"><span class="nav-icon">&#9678;</span> Dashboard</a></li>
      <li><a href="holdings.html"><span class="nav-icon">&#9828;</span> Holdings</a></li>
      <li><a href="copilot.html" class="active"><span class="nav-icon">&#11041;</span> Copilot</a></li>
    </ul>
```

- [ ] **Step 6: Verify**

Run:
```bash
git status --short src/main/resources/static/
grep -c "<li>" src/main/resources/static/dashboard.html src/main/resources/static/holdings.html src/main/resources/static/copilot.html
```
Expected: `analysis.html`/`strategy.html` show as deleted (`D`); each of the three `grep -c` counts is `3`.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/resources/static/analysis.html src/main/resources/static/strategy.html \
  src/main/resources/static/dashboard.html src/main/resources/static/holdings.html src/main/resources/static/copilot.html
git commit -m "feat: remove Analysis and Strategy pages, trim nav to Dashboard/Holdings/Copilot"
```

---

## Task 5: Delete the dead `app.js` and its includes

**Files:**
- Delete: `src/main/resources/static/scripts/app.js`
- Modify: `src/main/resources/static/dashboard.html:70-71`
- Modify: `src/main/resources/static/holdings.html:61-62`
- Modify: `src/main/resources/static/copilot.html:366-368`

- [ ] **Step 1: Confirm `app.js` has no remaining callers**

Run:
```bash
grep -rn "renderStockInsight\|renderStrategy\|renderHoldingsTable\|toggleSidebar\|showLoading(\|showError(" src/main/resources/static/*.html
```
Expected: no matches in `dashboard.html`, `holdings.html`, or `copilot.html` (they define their own equivalents inline, or — for `copilot.html` — `copilot.js` already shadows `showLoading`/`showError` with its own versions since it loads after `app.js`).

- [ ] **Step 2: Delete `app.js`**

```bash
rm src/main/resources/static/scripts/app.js
```

- [ ] **Step 3: Remove its `<script>` include from `dashboard.html`**

Replace:

```html
  <script src="scripts/api.js"></script>
  <script src="scripts/app.js"></script>
  <script>
```

with:

```html
  <script src="scripts/api.js"></script>
  <script>
```

- [ ] **Step 4: Remove its `<script>` include from `holdings.html`**

Replace:

```html
  <script src="scripts/api.js"></script>
  <script src="scripts/app.js"></script>
  <script>
```

with:

```html
  <script src="scripts/api.js"></script>
  <script>
```

- [ ] **Step 5: Remove its `<script>` include from `copilot.html`**

Replace:

```html
  <script src="scripts/api.js"></script>
  <script src="scripts/app.js"></script>
  <script src="scripts/copilot.js"></script>
```

with:

```html
  <script src="scripts/api.js"></script>
  <script src="scripts/copilot.js"></script>
```

- [ ] **Step 6: Verify no page references `app.js` anymore**

Run: `grep -rn "app.js" src/main/resources/static/`
Expected: no output (empty)

- [ ] **Step 7: Commit**

```bash
git add -A src/main/resources/static/scripts/app.js \
  src/main/resources/static/dashboard.html src/main/resources/static/holdings.html src/main/resources/static/copilot.html
git commit -m "chore: remove dead app.js (fully superseded by inline page scripts and copilot.js)"
```

---

## Task 6: Remove orphaned legacy functions from `api.js`

**Files:**
- Modify: `src/main/resources/static/scripts/api.js:47-93,176-196`

- [ ] **Step 1: Confirm these functions have no remaining callers**

Run:
```bash
grep -rn "API\.analyzeStock\|API\.analyzePortfolio\|API\.getLongTermStrategy\|API\.getShortTermStrategy\|API\.getAgents(\|API\.customAnalysis\|getRatingClass(\|getRatingDisplay(" src/main/resources/static/
```
Expected: no output (empty) — `analysis.html`/`strategy.html`/`app.js` were the only callers and are now deleted. (`API.copilotGetAgents` is a separate function and is unaffected.)

- [ ] **Step 2: Remove the six orphaned `API` methods**

Replace:

```javascript
    async analyzeStock(symbol, type = 'COMPREHENSIVE') {
        const response = await fetch(`${this.baseUrl}/api/analysis/stock/${symbol}?type=${type}`, {
            method: 'POST'
        });
        return response.json();
    },

    async analyzePortfolio() {
        const response = await fetch(`${this.baseUrl}/api/analysis/portfolio`, {
            method: 'POST'
        });
        return response.json();
    },

    async getLongTermStrategy(riskTolerance = 'MEDIUM', primaryGoal = 'WEALTH_CREATION') {
        const response = await fetch(`${this.baseUrl}/api/analysis/strategy/long-term`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ riskTolerance, primaryGoal })
        });
        return response.json();
    },

    async getShortTermStrategy() {
        const response = await fetch(`${this.baseUrl}/api/analysis/strategy/short-term`, {
            method: 'POST'
        });
        return response.json();
    },

    async getAgents() {
        const response = await fetch(`${this.baseUrl}/api/analysis/agents`);
        return response.json();
    },

    async customAnalysis(request) {
        const response = await fetch(`${this.baseUrl}/api/analysis/custom`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            throw new Error('Analysis request failed');
        }
        return response.json();
    },

    // Copilot endpoints
```

with:

```javascript
    // Copilot endpoints
```

- [ ] **Step 3: Remove the two orphaned rating helper functions**

Replace:

```javascript
function getRatingClass(rating) {
    const ratingMap = {
        'STRONG_BUY': 'rating-strong-buy',
        'BUY': 'rating-buy',
        'HOLD': 'rating-hold',
        'SELL': 'rating-sell',
        'STRONG_SELL': 'rating-strong-sell'
    };
    return ratingMap[rating] || 'rating-hold';
}

function getRatingDisplay(rating) {
    const displayMap = {
        'STRONG_BUY': 'Strong Buy',
        'BUY': 'Buy',
        'HOLD': 'Hold',
        'SELL': 'Sell',
        'STRONG_SELL': 'Strong Sell'
    };
    return displayMap[rating] || rating;
}
```

with nothing (delete these two functions; they were the last lines in the file, so remove the trailing blank lines they leave behind too).

- [ ] **Step 4: Verify the file still parses and the app still starts**

Run: `node --check src/main/resources/static/scripts/api.js && echo "syntax OK"`
Expected: `syntax OK`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/scripts/api.js
git commit -m "chore: remove api.js functions orphaned by Analysis/Strategy page removal"
```

---

## Task 7: Prune orphaned CSS from `main.css`

**Files:**
- Modify: `src/main/resources/static/styles/main.css` (badges block ~L508-556, stock-badge block ~L1003-1041)

These two blocks are confirmed used only by the now-deleted `strategy.html` (verified by grep in Task 4/5 context — `.badge`/`.badge-*`/`.stock-badge*` do not appear anywhere in `dashboard.html`, `holdings.html`, or `copilot.html`; `copilot.html` has its own separate `.meta-badge`/`.copilot-meta-badges` classes that are untouched by this task).

- [ ] **Step 1: Re-confirm zero remaining usages before deleting (files have changed since Task 4)**

Run:
```bash
grep -rn 'class="[^"]*\bbadge\b\|class="[^"]*\bbadge-\|class="[^"]*\bstock-group\b\|class="[^"]*\bstock-badge' src/main/resources/static/*.html src/main/resources/static/scripts/*.js
```
Expected: no output (empty)

- [ ] **Step 2: Remove the Badges block**

Replace:

```css
/* --- Badges --- */
.badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 2px var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: 0.72rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-family: var(--font-body);
}

.badge-success {
  background: var(--success-muted);
  color: var(--success);
}

.badge-danger {
  background: var(--danger-muted);
  color: var(--danger);
}

.badge-warning {
  background: var(--warning-muted);
  color: var(--warning);
}

.badge-info {
  background: var(--info-muted);
  color: var(--info);
}

.badge-accent {
  background: var(--accent-muted);
  color: var(--accent);
}

.badge-neutral {
  background: var(--bg-tertiary);
  color: var(--text-secondary);
}

/* Legacy badge aliases (used by strategy rendering) */
.badge-buy { background: var(--success-muted); color: var(--success); }
.badge-hold { background: var(--warning-muted); color: var(--warning); }
.badge-sell { background: var(--danger-muted); color: var(--danger); }
```

with nothing (delete the whole block, including the `/* --- Badges --- */` comment).

- [ ] **Step 3: Remove the stock-badge block**

Replace:

```css
.stock-group {
  margin-bottom: var(--space-lg);
}

.stock-group h4 {
  margin-bottom: var(--space-sm);
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.stock-badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.stock-badge {
  padding: var(--space-xs) var(--space-md);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 0.8rem;
  font-weight: 500;
}

.stock-badge.buy {
  background: var(--success-muted);
  color: var(--success);
}

.stock-badge.hold {
  background: var(--warning-muted);
  color: var(--warning);
}

.stock-badge.sell {
  background: var(--danger-muted);
  color: var(--danger);
}
```

with nothing (delete the whole block). Note: this block sits between other rules (there is a `.stock-group`-adjacent section before it and `/* --- Sector Allocation --- */` after it) — remove only the rules shown above, leave the surrounding sections intact.

- [ ] **Step 4: Verify the app still serves the stylesheet without error**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/styles/main.css`
Expected: `200`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/styles/main.css
git commit -m "chore: prune CSS orphaned by Analysis/Strategy page removal"
```

---

## Task 8: Manual verification pass

**Files:** none (verification only)

- [ ] **Step 1: Restart the server**

```bash
export JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
set -a && source .env && set +a
./mvnw spring-boot:run
```
(Or reuse the project's `run` skill if one exists by this point.)

- [ ] **Step 2: Confirm the deleted pages 404**

Run:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/analysis.html
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/strategy.html
```
Expected: `404` for both.

- [ ] **Step 3: Drive the app with Playwright and screenshot each hand-off**

If `playwright` isn't already installed for this project, install it in a scratch directory first:
```bash
mkdir -p /tmp/copilot-consolidation-verify && cd /tmp/copilot-consolidation-verify
npm init -y >/dev/null 2>&1
npm install playwright@1.61.1 --no-audit --no-fund
npx playwright install chromium
```

Write `/tmp/copilot-consolidation-verify/verify.js`:

```javascript
const { chromium } = require('playwright');

const BASE = 'http://localhost:8080';
const OUT = '/tmp/copilot-consolidation-verify/screenshots';
require('fs').mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`[pageerror] ${e.message}`));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(`[console] ${msg.text()}`); });

  const checks = [];
  function check(name, cond) { checks.push({ name, pass: !!cond }); }

  // Demo login
  await page.goto(`${BASE}/index.html`, { waitUntil: 'networkidle' });
  await page.locator('#demoBtn, #demo-login').first().click();
  await page.waitForLoadState('networkidle');

  // 1. Dashboard nav has exactly 3 items
  await page.goto(`${BASE}/dashboard.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}/01-dashboard.png` });
  check('dashboard nav has 3 items', await page.locator('.navbar-nav > li').count() === 3);

  // 2. "Analyze a Stock" -> copilot.html, no query pre-filled
  await page.click('text=Analyze a Stock');
  await page.waitForLoadState('networkidle');
  check('Analyze a Stock lands on copilot.html', page.url().includes('copilot.html'));
  check('Analyze a Stock has no pre-filled query', (await page.locator('#copilotInput').inputValue()) === '');
  await page.screenshot({ path: `${OUT}/02-analyze-a-stock.png` });

  // 3. "Long-Term Strategy" -> pre-filled + auto-sent
  await page.goto(`${BASE}/dashboard.html`, { waitUntil: 'networkidle' });
  await page.click('text=Long-Term Strategy');
  await page.waitForSelector('.copilot-result', { timeout: 30000 });
  check('Long-Term Strategy auto-sent and rendered a result', await page.locator('.copilot-result').count() > 0);
  await page.screenshot({ path: `${OUT}/03-long-term-strategy.png`, fullPage: true });

  // 4. "Short-Term Strategy" -> pre-filled + auto-sent
  await page.goto(`${BASE}/dashboard.html`, { waitUntil: 'networkidle' });
  await page.click('text=Short-Term Strategy');
  await page.waitForSelector('.copilot-result', { timeout: 30000 });
  check('Short-Term Strategy auto-sent and rendered a result', await page.locator('.copilot-result').count() > 0);
  await page.screenshot({ path: `${OUT}/04-short-term-strategy.png`, fullPage: true });

  // 5. Holdings row "Analyze" -> symbol selected + auto-sent
  await page.goto(`${BASE}/holdings.html`, { waitUntil: 'networkidle' });
  check('holdings nav has 3 items', await page.locator('.navbar-nav > li').count() === 3);
  const firstSymbol = await page.locator('#holdingsBody tr').first().locator('.col-symbol').textContent();
  await page.locator('#holdingsBody tr').first().locator('button:has-text("Analyze")').click();
  await page.waitForSelector('.copilot-result', { timeout: 30000 });
  check('Holdings Analyze selected the right symbol', (await page.locator('#symbolSelect').inputValue()) === firstSymbol.trim());
  check('Holdings Analyze auto-sent and rendered a result', await page.locator('.copilot-result').count() > 0);
  await page.screenshot({ path: `${OUT}/05-holdings-analyze.png`, fullPage: true });

  // 6. Zero console/page errors across the whole walkthrough
  check('zero console/page errors', errors.length === 0);

  console.log(JSON.stringify({ checks, errors }, null, 2));
  await browser.close();
  process.exit(checks.some(c => !c.pass) ? 1 : 0);
})();
```

Run: `cd /tmp/copilot-consolidation-verify && node verify.js`
Expected: every entry in the printed `checks` array has `"pass": true`, `errors` is `[]`, and the process exits `0`.

- [ ] **Step 4: Report results to the user**

Summarize pass/fail for each of the 6 checks above. If everything passes, this plan is complete — no commit needed for this task since it makes no file changes.
