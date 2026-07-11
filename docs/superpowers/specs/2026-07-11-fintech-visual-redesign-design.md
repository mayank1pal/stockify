# Fintech Visual Redesign — Design

## Problem

StockMan's current visual design ("Black & Gold Terminal") uses a warm amber/gold accent on near-black, paired with an editorial serif display font (Cormorant Garamond). It's distinctive, but the user wants a calmer, more conventional fintech tone: "calm, trustworthy, professional... premium but not flashy." This redesign changes the color palette and typography system to match, while leaving layout, component structure, and spacing untouched.

Reference material used to inform this design (pulled as read-only guidance, not installed as Claude Code plugins):
- `bencium-controlled-ux-designer` (bencium/bencium-marketplace) — systematic UX methodology: 4-5 neutral shades + 1-3 accent colors, mathematical type/spacing scales, WCAG AA baseline.
- `ui-ux-pro-max` (nextlevelbuilder/ui-ux-pro-max-skill) — fintech-specific color/product data. The "Personal Finance Tracker" archetype (`colors.csv` row 91) was selected via visual comparison in the brainstorming session: `Primary #1E40AF`, `Accent #059669`, dark background `#0F172A`, explicitly annotated "Calm blue + success green + alert red" in the source data — directly matching the requested tone.

## Scope

All 4 pages (`index.html`, `dashboard.html`, `holdings.html`, `copilot.html`). Colors and font-family are centralized in `styles/main.css` CSS custom properties, so changing `:root` cascades everywhere with no per-page edits needed. Font-size normalization touches individual declarations within the same file. No HTML structure, layout, component markup, or spacing changes.

## Color System

New `:root` values in `src/main/resources/static/styles/main.css`, replacing the existing gold/black values. Variable *names* are unchanged — only their values — so no selector in any HTML file needs to change.

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
  --warning: #f59e0b;       /* unchanged — still distinct from accent */
  --warning-muted: rgba(245, 158, 11, 0.12);
  --info: #38bdf8;          /* unchanged — kept distinct from --accent so info callouts don't visually collide with primary action blue */
  --info-muted: rgba(56, 189, 248, 0.12);
}
```

**Accessibility verification (already computed for this spec, via WCAG relative-luminance contrast ratio — see table below):**

| Pairing | Ratio | AA normal text (≥4.5:1) |
|---|---|---|
| `--text-primary` on `--bg-primary` | 17.06:1 | Pass |
| `--text-secondary` on `--bg-secondary` (card) | 6.26:1 | Pass |
| White on `--accent` (button text) | 8.72:1 | Pass |
| `--text-accent` on `--bg-primary` / `--bg-secondary` | 7.02:1 / 6.31:1 | Pass |
| `--success` on `--bg-primary` / `--bg-secondary` | 7.83:1 / 7.04:1 | Pass |
| `--danger` on `--bg-primary` / `--bg-secondary` | 6.45:1 / 5.80:1 | Pass |

Two values were changed from the raw reference-data hex codes specifically because they failed this check: the CSV's `--success: #059669` measured 4.26:1 on card backgrounds (fails 4.5:1) — replaced with `#22C55E` (the app's *current* success green, which happens to already satisfy AA here, so this is a zero-diff carryover, not a new value). The CSV's `--danger: #DC2626` measured as low as 3.32:1 (fails badly) — replaced with `#F87171`, a lighter shade in the same red hue family. `--text-accent` was set to `#60A5FA` rather than the earlier-considered `#3B82F6`, since `#3B82F6` measured 4.36:1 on card backgrounds (fails by a small margin) while `#60A5FA` clears it comfortably. `--accent` (#1E40AF) itself is only ever used as a background/border color (button fills, active-state borders), never as foreground text directly on `--bg-primary`, so its low contrast as a hypothetical text color (2.05:1) is not applicable to its actual usage.

## Typography

**Font family** — drop `Cormorant Garamond` entirely:

```css
--font-display: 'Hanken Grotesk', system-ui, sans-serif;
--font-body: 'Hanken Grotesk', system-ui, sans-serif;
--font-mono: 'JetBrains Mono', 'Fira Code', monospace;  /* unchanged */
```

Remove the `Cormorant+Garamond` weights from the `@import` line at the top of `main.css`, keeping only `Hanken+Grotesk` and the JetBrains Mono import (whichever font service is used for that one).

**Type scale** — introduce 8 new tokens (1.25x ratio / "major third", 16px base), and remap every existing hardcoded `font-size` declaration in `main.css` to its nearest step:

```css
--text-xs: 0.64rem;    /* 10px */
--text-sm: 0.8rem;     /* 13px */
--text-base: 1rem;     /* 16px */
--text-lg: 1.25rem;    /* 20px */
--text-xl: 1.563rem;   /* 25px */
--text-2xl: 1.953rem;  /* 31px */
--text-3xl: 2.441rem;  /* 39px */
--text-4xl: 3.052rem;  /* 49px */
```

Remapping principle: for each existing `font-size: <value>` declaration, replace the hardcoded value with `var(--text-*)` for whichever step is numerically closest. Examples from the current file: `h1 { font-size: 2.4rem }` → `var(--text-3xl)` (2.441rem); `h2 { font-size: 1.8rem }` → `var(--text-2xl)` (1.953rem, since 2xl is closer than xl's 1.563); `h3 { font-size: 1.4rem }` → `var(--text-xl)` (1.563rem); stat-value sizes at `1.4rem`–`1.6rem` → `var(--text-xl)`; small labels at `0.65rem`–`0.78rem` → `var(--text-xs)` or `var(--text-sm)` depending on which is numerically closer per-instance. This is a mechanical per-declaration judgment call, not a formula — the implementation plan enumerates each one individually rather than applying a blanket regex, since a handful of sizes (e.g. `0.87rem` vs `0.88rem` vs `0.9rem`) are close enough to either neighbor that context (is it a label vs. a value?) should break the tie.

**Not changing:** line-height, letter-spacing, and font-weight rules stay as they are — only the `font-size` value and `font-family` values are in scope.

## Spacing

No change. The existing scale (`--space-xs: 4px` through `--space-3xl: 64px`, progressing 4/8/16/24/32/48/64) already satisfies the "mathematical spacing scale" requirement — confirmed by inspection, not something this redesign needs to touch.

## Testing / Verification

No backend change, no new unit tests. Verification is the same Playwright-driven walkthrough pattern used earlier in this project:
1. Screenshot all 4 pages (login, dashboard, holdings, copilot) in demo mode, confirm the new palette and typography render as expected and nothing looks visually broken (overlapping text, unreadable contrast, layout shifts from font-size changes).
2. Confirm the implemented hex values in `main.css` match the accessibility-verified table above exactly (the ratios were already computed during design, not left for implementation time to discover) — a plain `grep` for each `--success`/`--danger`/`--text-accent`/`--accent` value is sufficient, no need to recompute contrast ratios.
3. Confirm zero browser console errors across all 4 pages (catches any stray reference to the removed serif font or a typo'd CSS variable name).
