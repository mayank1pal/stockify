# Fintech Visual Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace StockMan's amber/gold "Black & Gold Terminal" palette and serif+sans typography with a calm Trust Blue + Profit Green palette and all-sans typography, plus introduce a mathematical type scale, without changing any HTML structure, layout, or spacing.

**Architecture:** Single-file change to `src/main/resources/static/styles/main.css`'s `:root` custom properties (colors cascade to all 4 pages automatically since every selector already references `var(--*)` tokens), plus a handful of hardcoded color literals outside `:root` that don't use tokens and would otherwise miss the palette swap, plus one hardcoded hex in `dashboard.html`'s inline JS. Type-scale normalization touches ~56 individual `font-size` declarations in the same CSS file, each remapped to the nearest step of a new 8-step scale.

**Tech Stack:** Plain CSS custom properties, no build step, no preprocessor. Verification via the same Playwright pattern used earlier in this project.

## Global Constraints

- Frontend-only — no backend/Java code changes.
- All 4 pages (`index.html`, `dashboard.html`, `holdings.html`, `copilot.html`) must reflect the new palette.
- No new font family introduced — reuse `Hanken Grotesk` (already loaded) for both `--font-display` and `--font-body`; drop `Cormorant Garamond` entirely.
- Font-size changes only via the new `--text-xs` through `--text-4xl` tokens (1.25x ratio, 1rem base) — `line-height`, `letter-spacing`, and `font-weight` are untouched on every rule.
- Spacing scale (`--space-*`) is untouched — already compliant, confirmed during design.
- No HTML structure, layout, or component-markup changes anywhere.
- Final `--success`/`--danger`/`--text-accent` hex values must exactly match the WCAG-AA-verified values from the design spec (`docs/superpowers/specs/2026-07-11-fintech-visual-redesign-design.md`) — do not substitute different shades even if they look similar.

---

## Task 1: Color palette + font-family swap

**Files:**
- Modify: `src/main/resources/static/styles/main.css:1-50` (header comment, `@import`, `:root` block)
- Modify: `src/main/resources/static/styles/main.css:379,725,1277,1278` (hardcoded gold `rgba()` literals outside `:root`)
- Modify: `src/main/resources/static/dashboard.html:235` (hardcoded gold hex in a JS color array)

**Interfaces:**
- Produces: new values for all existing CSS custom property *names* in `:root` (no names change, only values) — `--bg-primary`, `--bg-secondary`, `--bg-tertiary`, `--border-accent`, `--text-primary`, `--text-secondary`, `--text-accent`, `--accent`, `--accent-hover`, `--accent-muted`, `--accent-glow`, `--success`, `--success-muted`, `--danger`, `--danger-muted`, `--font-display`, `--font-body`. Every other file in the project already consumes these by name and needs no changes.

- [ ] **Step 1: Update the file header comment**

Replace:

```css
/* ============================================================
   StockMan — "Black & Gold Terminal" Design System
   Premium financial interface: editorial serif + dense data
   ============================================================ */
```

with:

```css
/* ============================================================
   StockMan — "Trust & Clarity" Design System
   Calm, professional fintech interface: all-sans + dense data
   ============================================================ */
```

- [ ] **Step 2: Drop the serif font from the `@import`**

Replace:

```css
@import url('https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,400;0,500;0,600;0,700;1,400&family=Hanken+Grotesk:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap');
```

with:

```css
@import url('https://fonts.googleapis.com/css2?family=Hanken+Grotesk:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap');
```

- [ ] **Step 3: Replace the `:root` color and font-family values**

Replace:

```css
:root {
  /* Backgrounds */
  --bg-primary: #09090b;
  --bg-secondary: #131316;
  --bg-tertiary: #1a1a1f;
  --bg-elevated: #202027;
  --bg-hover: #25252d;

  /* Borders */
  --border-subtle: #1f1f26;
  --border-default: #2a2a33;
  --border-strong: #3a3a45;
  --border-accent: #e8a838;

  /* Text */
  --text-primary: #e4e4e7;
  --text-secondary: #8e8e96;
  --text-muted: #56565e;
  --text-accent: #e8a838;
  --text-inverse: #09090b;

  /* Accent — Warm Gold */
  --accent: #e8a838;
  --accent-hover: #d4952e;
  --accent-muted: rgba(232, 168, 56, 0.12);
  --accent-glow: rgba(232, 168, 56, 0.25);

  /* Semantic */
  --success: #22c55e;
  --success-muted: rgba(34, 197, 94, 0.12);
  --danger: #ef4444;
  --danger-muted: rgba(239, 68, 68, 0.12);
  --warning: #f59e0b;
  --warning-muted: rgba(245, 158, 11, 0.12);
  --info: #38bdf8;
  --info-muted: rgba(56, 189, 248, 0.12);

  /* Typography */
  --font-display: 'Cormorant Garamond', Georgia, serif;
  --font-body: 'Hanken Grotesk', system-ui, sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
```

with:

```css
:root {
  /* Backgrounds */
  --bg-primary: #0F172A;
  --bg-secondary: #192134;
  --bg-tertiary: #1A2338;
  --bg-elevated: #202B45;
  --bg-hover: #253150;

  /* Borders */
  --border-subtle: rgba(255, 255, 255, 0.06);
  --border-default: rgba(255, 255, 255, 0.10);
  --border-strong: rgba(255, 255, 255, 0.16);
  --border-accent: #1E40AF;

  /* Text */
  --text-primary: #F8FAFC;
  --text-secondary: #94A3B8;
  --text-muted: #64748B;
  --text-accent: #60A5FA;
  --text-inverse: #0F172A;

  /* Accent — Trust Blue */
  --accent: #1E40AF;
  --accent-hover: #1E3A8A;
  --accent-muted: rgba(30, 64, 175, 0.12);
  --accent-glow: rgba(30, 64, 175, 0.25);

  /* Semantic */
  --success: #22C55E;
  --success-muted: rgba(34, 197, 94, 0.12);
  --danger: #F87171;
  --danger-muted: rgba(248, 113, 113, 0.12);
  --warning: #f59e0b;
  --warning-muted: rgba(245, 158, 11, 0.12);
  --info: #38bdf8;
  --info-muted: rgba(56, 189, 248, 0.12);

  /* Typography */
  --font-display: 'Hanken Grotesk', system-ui, sans-serif;
  --font-body: 'Hanken Grotesk', system-ui, sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
```

Note: `--success` keeps its exact existing value (`#22C55E`) — the design spec's accessibility check found the reference data's darker green failed WCAG AA on card backgrounds, and this pre-existing value already passes, so it's carried over unchanged rather than replaced.

- [ ] **Step 4: Fix the 4 hardcoded gold `rgba()` literals that don't use tokens**

These reference the raw gold RGB `(232, 168, 56)` directly instead of `var(--accent-glow)`/`var(--accent-muted)`, so Step 3 alone won't update them. The new accent `#1E40AF` is RGB `(30, 64, 175)`.

Line 379 — replace:

```css
  background: linear-gradient(135deg, var(--bg-secondary) 0%, rgba(232, 168, 56, 0.04) 100%);
```

with:

```css
  background: linear-gradient(135deg, var(--bg-secondary) 0%, rgba(30, 64, 175, 0.04) 100%);
```

Line 725 — replace:

```css
  border: 1px solid rgba(232, 168, 56, 0.2);
```

with:

```css
  border: 1px solid rgba(30, 64, 175, 0.2);
```

Lines 1277-1278 — replace:

```css
    radial-gradient(ellipse at 20% 50%, rgba(232, 168, 56, 0.06) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 50%, rgba(232, 168, 56, 0.03) 0%, transparent 50%),
```

with:

```css
    radial-gradient(ellipse at 20% 50%, rgba(30, 64, 175, 0.06) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 50%, rgba(30, 64, 175, 0.03) 0%, transparent 50%),
```

- [ ] **Step 5: Fix the hardcoded gold hex in `dashboard.html`'s sector-allocation chart colors**

Replace:

```javascript
      var colors = ['#e8a838', '#38bdf8', '#22c55e', '#ef4444', '#8b5cf6', '#f59e0b'];
```

with:

```javascript
      var colors = ['#1E40AF', '#38bdf8', '#22c55e', '#ef4444', '#8b5cf6', '#f59e0b'];
```

(Only the first color changes — it was the old accent gold used as the first sector-chart color. The rest are an arbitrary categorical palette unrelated to the accent/semantic tokens and stay as-is.)

- [ ] **Step 6: Verify no gold references remain**

Run: `grep -rn "e8a838\|d4952e\|232, 168, 56" src/main/resources/static/`
Expected: no output (empty)

- [ ] **Step 7: Verify the app still starts and serves all 4 pages**

```bash
export JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
set -a && source .env && set +a
nohup ./mvnw spring-boot:run > /tmp/stockman-server.log 2>&1 &
```

Poll until ready, then check each page:
```bash
until curl -sf http://localhost:8080/index.html >/dev/null; do sleep 1; done
curl -s -o /dev/null -w "index: %{http_code}\n" http://localhost:8080/index.html
curl -s -o /dev/null -w "dashboard: %{http_code}\n" http://localhost:8080/dashboard.html
curl -s -o /dev/null -w "holdings: %{http_code}\n" http://localhost:8080/holdings.html
curl -s -o /dev/null -w "copilot: %{http_code}\n" http://localhost:8080/copilot.html
```
Expected: `200` for all four.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/static/styles/main.css src/main/resources/static/dashboard.html
git commit -m "feat(design): swap amber/gold palette for Trust Blue + Profit Green, drop serif font"
```

---

## Task 2: Mathematical type scale

**Files:**
- Modify: `src/main/resources/static/styles/main.css` (`:root` block — add 8 new tokens; ~56 individual `font-size` declarations throughout the file)

**Interfaces:**
- Consumes: `--font-display`/`--font-body` values from Task 1 (unaffected by this task, just noting the dependency — Task 1 must land first).
- Produces: `--text-xs` through `--text-4xl` tokens in `:root`, used only within this task's own edits (no later task depends on them).

**Mapping principle:** each existing hardcoded `font-size: <value>` is replaced with `var(--text-*)` for whichever of the 8 steps below is numerically closest to the old value. Two explicit exceptions, stated once here rather than repeated per-line:
1. **Ties resolved toward legibility, not just nearest-neighbor**, for anything that is read as continuous text (paragraphs, chat bubbles, form inputs) rather than a label: when a value sits roughly equidistant between two steps, the base step wins. This includes one case bencium's own reference guidance calls out directly: form input text should be 16px minimum to prevent iOS Safari auto-zooming the page on focus — `--text-base` is exactly 1rem/16px, so any form-input tie resolves there.
2. **Ties resolved toward prominence** for heading-like or brand elements (page/section headings, the navbar brand wordmark, the mobile nav-toggle icon): resolve to the larger step, matching the "premium, confident" tone goal rather than shrinking headings down.
3. `.text-mono`'s `0.9em` (main.css:140) is a *relative* multiplier of whatever font-size surrounds it, not an absolute size — left as `em`, not converted to a `--text-*` token, since doing so would change its behavior from "90% of parent" to a fixed absolute size.
4. The "Scanner Styles" block (main.css:1128-1262, selectors like `.signal-card`, `.metric-label`, `.status-indicator`) is **not touched by this task**. It references undefined custom properties (`--surface-alt`, `--border`) that don't exist anywhere in this file's `:root`, and there is no `scanner.html` on this branch that could render it — it's dead CSS left over from unmerged scanner-feature work on a different branch. Remapping its font-sizes would add risk for zero visible effect.

**Critical editing note — do not search-replace bare `font-size:` lines in isolation.** The same font-size value repeats verbatim across multiple unrelated selectors in this file — for example `font-size: 0.95rem;` appears in four different rules (`.page-header .subtitle`, `.trade-plan-item .value`, `.login-actions .btn`, `.empty-state .empty-text`), and `font-size: 0.78rem;` appears in five (`.card-header .card-label`, `.stat-card .stat-label`, `.form-label`, `.meta-badge`, `.strategy-option .horizon`). Every step below names the exact selector the change applies to. Before each edit, locate that specific selector's rule in the current file (its name is unique even when its font-size value isn't) and include enough of that rule as the edit's matched text — at minimum the selector line itself alongside the font-size line — so the replacement cannot land on a different selector that happens to share the same old value. Also note: Step 1 inserts 8 new lines into `:root`, so every line number cited in Steps 2 onward refers to the file's state *before* Step 1 ran and will be off by +8 afterward — treat every line number in this task as a locator hint to find the right selector, never as a literal offset to edit blindly.

- [ ] **Step 1: Add the type-scale tokens to `:root`**

Insert this block into `:root`, immediately after the `/* Typography */` block added in Task 1 (after the `--font-mono` line, before the `/* Spacing */` comment):

```css
  --text-xs: 0.64rem;
  --text-sm: 0.8rem;
  --text-base: 1rem;
  --text-lg: 1.25rem;
  --text-xl: 1.563rem;
  --text-2xl: 1.953rem;
  --text-3xl: 2.441rem;
  --text-4xl: 3.052rem;
```

- [ ] **Step 2: Remap the base heading elements (lines 133-136)**

Replace:

```css
h1 { font-size: 2.4rem; font-weight: 700; }
h2 { font-size: 1.8rem; }
h3 { font-size: 1.4rem; }
h4 { font-size: 1.15rem; font-weight: 500; }
```

with:

```css
h1 { font-size: var(--text-3xl); font-weight: 700; }
h2 { font-size: var(--text-2xl); }
h3 { font-size: var(--text-xl); }
h4 { font-size: var(--text-lg); font-weight: 500; }
```

- [ ] **Step 3: Remap the navbar brand and nav elements (lines 169, 187, 206, 224, 239, 253, 264)**

Replace line 169 `.navbar-brand { ... font-size: 1.4rem; ... }`'s font-size line:
```css
  font-size: 1.4rem;
```
with:
```css
  font-size: var(--text-xl);
```
(this is the first `font-size: 1.4rem;` in the file after line 136 — the one inside the `.navbar-brand` rule that starts at line 164)

Replace line 187 (`.navbar-brand .brand-mark`):
```css
  font-size: 1rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 206 (`.navbar-nav a`):
```css
  font-size: 0.87rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 224 (`.navbar-nav a .nav-icon`):
```css
  font-size: 1.05rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 239 (`.navbar-user`):
```css
  font-size: 0.85rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 253 (`.navbar-user .avatar`):
```css
  font-size: 0.75rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 264 (`.nav-toggle`):
```css
  font-size: 1.4rem;
```
with:
```css
  font-size: var(--text-xl);
```

- [ ] **Step 4: Remap page-header, card, and stat-card sizes (lines 291, 319, 323, 355, 364, 373)**

Replace line 291 (`.page-header .subtitle`):
```css
  font-size: 0.95rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 319 (`.card-header h3`):
```css
  font-size: 1.1rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 323 (`.card-header .card-label`):
```css
  font-size: 0.78rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 355 (`.stat-card .stat-label`):
```css
  font-size: 0.78rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 364 (`.stat-card .stat-value`):
```css
  font-size: 1.6rem;
```
with:
```css
  font-size: var(--text-xl);
```

Replace line 373 (`.stat-card .stat-change`):
```css
  font-size: 0.82rem;
```
with:
```css
  font-size: var(--text-sm);
```

- [ ] **Step 5: Remap table, button, and badge sizes (lines 386, 398, 430, 448, 493, 498, 515)**

Replace line 386 (`.data-table`):
```css
  font-size: 0.88rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 398 (`.data-table th`):
```css
  font-size: 0.72rem;
```
with:
```css
  font-size: var(--text-xs);
```

Replace line 430 (`.data-table .col-number`):
```css
  font-size: 0.85rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 448 (`.btn`):
```css
  font-size: 0.87rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 493 (`.btn-sm`):
```css
  font-size: 0.8rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 498 (`.btn-lg`):
```css
  font-size: 1rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 515 (`.badge`):
```css
  font-size: 0.72rem;
```
with:
```css
  font-size: var(--text-xs);
```

- [ ] **Step 6: Remap form, agent-card, and chat sizes (lines 565, 580, 649, 656, 662, 676, 719)**

Replace line 565 (`.form-label`):
```css
  font-size: 0.78rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 580 (`.form-input, .form-select, .form-textarea`):
```css
  font-size: 0.9rem;
```
with:
```css
  font-size: var(--text-base);
```
(this is the iOS-zoom-prevention exception from the mapping principle above — 16px minimum on form inputs)

Replace line 649 (`.agent-card .agent-icon`):
```css
  font-size: 1.5rem;
```
with:
```css
  font-size: var(--text-xl);
```

Replace line 656 (`.agent-card .agent-name`):
```css
  font-size: 0.85rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 662 (`.agent-card .agent-desc`):
```css
  font-size: 0.75rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 676 (`.agent-tag`):
```css
  font-size: 0.65rem;
```
with:
```css
  font-size: var(--text-xs);
```

Replace line 719 (`.chat-bubble`):
```css
  font-size: 0.9rem;
```
with:
```css
  font-size: var(--text-base);
```
(reading-comfort exception — this is a message body, not a label)

- [ ] **Step 7: Remap chip, copilot-response, trade-plan, and reasoning-chain sizes (lines 771, 809, 870, 879, 915, 922, 932)**

Replace line 771 (`.chip`):
```css
  font-size: 0.8rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 809 (`.meta-badge`):
```css
  font-size: 0.78rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 870 (`.trade-plan-item .label`):
```css
  font-size: 0.7rem;
```
with:
```css
  font-size: var(--text-xs);
```

Replace line 879 (`.trade-plan-item .value`):
```css
  font-size: 0.95rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 915 (`.reasoning-header .agent-label`):
```css
  font-size: 0.88rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 922 (`.reasoning-header .toggle-icon`):
```css
  font-size: 0.8rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 932 (`.reasoning-body`):
```css
  font-size: 0.88rem;
```
with:
```css
  font-size: var(--text-sm);
```

- [ ] **Step 8: Remap dissent-box, strategy, sector, and empty-state sizes (lines 955, 987, 1071, 1078, 1123, 1265)**

Replace line 955 (`.dissent-box .dissent-title`):
```css
  font-size: 0.85rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 987 (`.strategy-option .horizon`):
```css
  font-size: 0.78rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 1071 (`.sector-name`):
```css
  font-size: 0.82rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 1078 (`.sector-pct`):
```css
  font-size: 0.8rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 1123 (`.empty-state .empty-icon`):
```css
  font-size: 2.5rem;
```
with:
```css
  font-size: var(--text-3xl);
```

Replace line 1265 (`.empty-state .empty-text`):
```css
  font-size: 0.95rem;
```
with:
```css
  font-size: var(--text-base);
```

- [ ] **Step 9: Remap login and quick-action sizes (lines 1310, 1315, 1320, 1331, 1351, 1373, 1384)**

Replace line 1310 (`.login-brand .brand-mark`):
```css
  font-size: 1.6rem;
```
with:
```css
  font-size: var(--text-xl);
```

Replace line 1315 (`.login-brand h1`):
```css
  font-size: 2rem;
```
with:
```css
  font-size: var(--text-2xl);
```

Replace line 1320 (`.login-subtitle`):
```css
  font-size: 0.92rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 1331 (`.login-divider`):
```css
  font-size: 0.8rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 1351 (`.login-actions .btn`):
```css
  font-size: 0.95rem;
```
with:
```css
  font-size: var(--text-base);
```

Replace line 1373 (`.quick-action`):
```css
  font-size: 0.87rem;
```
with:
```css
  font-size: var(--text-sm);
```

Replace line 1384 (`.quick-action .action-icon`):
```css
  font-size: 1.2rem;
```
with:
```css
  font-size: var(--text-lg);
```

- [ ] **Step 10: Remap the mobile responsive-override sizes (lines 1518, 1519, 1521)**

These are `@media (max-width: 768px)` overrides. Rule for responsive overrides: use the scale step one below the corresponding desktop token (already established above), not an independent nearest-neighbor calculation against the old literal value.

Replace:

```css
  h1 { font-size: 1.8rem; }
  h2 { font-size: 1.4rem; }

  .stat-card .stat-value { font-size: 1.3rem; }
```

with:

```css
  h1 { font-size: var(--text-2xl); }
  h2 { font-size: var(--text-xl); }

  .stat-card .stat-value { font-size: var(--text-lg); }
```

- [ ] **Step 11: Verify no unconverted absolute font-sizes remain outside the excluded Scanner Styles block and `.text-mono`**

Run:
```bash
awk 'NR<1128 || NR>1262' src/main/resources/static/styles/main.css | grep -n "font-size:" | grep -v "var(--text-\|0.9em"
```
Expected: no output (empty) — every remaining `font-size:` declaration outside the excluded ranges should now use a `var(--text-*)` token.

- [ ] **Step 12: Verify the app still serves all 4 pages**

```bash
curl -s -o /dev/null -w "index: %{http_code}\n" http://localhost:8080/index.html
curl -s -o /dev/null -w "dashboard: %{http_code}\n" http://localhost:8080/dashboard.html
curl -s -o /dev/null -w "holdings: %{http_code}\n" http://localhost:8080/holdings.html
curl -s -o /dev/null -w "copilot: %{http_code}\n" http://localhost:8080/copilot.html
```
Expected: `200` for all four. (Restart the server first if it isn't still running from Task 1 — same startup command as Task 1 Step 7.)

- [ ] **Step 13: Commit**

```bash
git add src/main/resources/static/styles/main.css
git commit -m "feat(design): introduce mathematical type scale, remap all font-sizes"
```

---

## Task 3: Verification

**Files:** none (verification only)

**Interfaces:**
- Consumes: the running app at `http://localhost:8080`, all 4 pages under `src/main/resources/static/`, and the WCAG contrast table from `docs/superpowers/specs/2026-07-11-fintech-visual-redesign-design.md`.

- [ ] **Step 1: Confirm the final color values match the spec's verified table exactly**

Run:
```bash
grep -n "\-\-success:\|\-\-danger:\|\-\-text-accent:\|\-\-accent:" src/main/resources/static/styles/main.css
```
Expected output (in this order, first occurrence of each in `:root`):
```
  --text-accent: #60A5FA;
  --accent: #1E40AF;
  --success: #22C55E;
  --danger: #F87171;
```
If any value differs, stop and fix it — these are the exact values the design spec's contrast-ratio table was computed against; a substituted "close enough" shade has not been verified for WCAG AA.

- [ ] **Step 2: Restart the server fresh**

```bash
pkill -f "spring-boot:run" 2>/dev/null; pkill -f "StockManApplication" 2>/dev/null; sleep 2
cd /Users/mayankpal/Documents/Projects/StockMan
export JAVA_HOME=/Users/mayankpal/Library/Java/JavaVirtualMachines/corretto-17.0.18/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw clean -q
set -a && source .env && set +a
nohup ./mvnw spring-boot:run > /tmp/stockman-server.log 2>&1 &
until curl -sf http://localhost:8080/index.html >/dev/null; do sleep 1; done
```

- [ ] **Step 3: Drive the app with Playwright and screenshot all 4 pages**

If `playwright` isn't already installed for this project, install it in a scratch directory:
```bash
mkdir -p /tmp/fintech-redesign-verify && cd /tmp/fintech-redesign-verify
npm init -y >/dev/null 2>&1
npm install playwright@1.61.1 --no-audit --no-fund
npx playwright install chromium
```

Write `/tmp/fintech-redesign-verify/verify.js`:

```javascript
const { chromium } = require('playwright');

const BASE = 'http://localhost:8080';
const OUT = '/tmp/fintech-redesign-verify/screenshots';
require('fs').mkdirSync(OUT, { recursive: true });

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(`[pageerror] ${e.message}`));
  page.on('console', (msg) => { if (msg.type() === 'error') errors.push(`[console] ${msg.text()}`); });

  const checks = [];
  function check(name, cond) { checks.push({ name, pass: !!cond }); }

  await page.goto(`${BASE}/index.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}/01-login.png` });
  const bg1 = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  check('login page background is dark navy (rgb(15, 23, 42))', bg1 === 'rgb(15, 23, 42)');

  await page.locator('#demoBtn, #demo-login').first().click();
  await page.waitForLoadState('networkidle');

  await page.goto(`${BASE}/dashboard.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}/02-dashboard.png` });
  const accentBg = await page.evaluate(() => {
    const btn = document.querySelector('.btn-primary, .quick-action');
    return btn ? getComputedStyle(btn).getPropertyValue('border-color') || getComputedStyle(document.documentElement).getPropertyValue('--accent') : null;
  });
  check('dashboard rendered without error', accentBg !== null);

  await page.goto(`${BASE}/holdings.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}/03-holdings.png` });

  await page.goto(`${BASE}/copilot.html`, { waitUntil: 'networkidle' });
  await page.screenshot({ path: `${OUT}/04-copilot.png` });

  const fontFamily = await page.evaluate(() => getComputedStyle(document.querySelector('h1')).fontFamily);
  check('h1 no longer uses Cormorant Garamond', !fontFamily.includes('Cormorant'));

  check('zero console/page errors', errors.length === 0);

  console.log(JSON.stringify({ checks, errors }, null, 2));
  await browser.close();
  process.exit(checks.some(c => !c.pass) ? 1 : 0);
})();
```

Run: `cd /tmp/fintech-redesign-verify && node verify.js`
Expected: every entry in `checks` has `"pass": true`, `errors` is `[]`, process exits `0`.

- [ ] **Step 4: Visually review the 4 screenshots**

Open `/tmp/fintech-redesign-verify/screenshots/01-login.png` through `04-copilot.png` and confirm: no serif text anywhere, no gold/amber anywhere, text is legible against the new dark-navy backgrounds, no obviously broken layout (overlapping text, clipped labels) introduced by the font-size changes.

- [ ] **Step 5: Report results**

Summarize pass/fail for each automated check and the visual review. If everything passes, this plan is complete — no commit needed for this task since it makes no file changes.
