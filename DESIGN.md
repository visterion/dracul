# DESIGN.md

> Visual identity and design system for Dracul — the web application,
> the documentation, and all visual assets.
> Stack: Vue 3 + Vuetify 3 + custom theme.

-----

## Part 1 — Design Philosophy

Dracul’s design language follows a single principle: **gothic substance, modern restraint**.

The project is thematically rooted in vampiric metaphor — Dracul the lord, his Strigoi, the night hunt, the blood moon. But it is also a serious piece of engineering: a Java agent system, an investment research tool, an Apache-2.0 framework. The visual language must hold both truths at once.

This means:

- **No Halloween clichés.** No dripping fonts, no cartoon fangs, no graveyard kitsch.
- **No bland fintech minimalism.** No sterile blue gradients, no generic “AI startup” aesthetic.
- **Gothic themes as undercurrents.** The vampire is implied through composition, color, and small details — not shouted through obvious imagery.
- **Restraint as confidence.** Dracul does not need to prove its theme. The theme is in the bones; the surface can afford to be quiet.

Reference points: Bloomberg Terminal meets Bram Stoker. Mike Mignola’s *Hellboy* covers, but cleaner. The *Financial Times* with a gothic editor. Castlevania concept art laid out by a Swiss typographer.

The application — Chronicle — is where this design language lives daily. It is the tool you wake up to every morning. It must be **beautiful enough to enjoy looking at**, **calm enough to read concentrated information**, and **distinctive enough to feel like a place, not a dashboard**.

-----

## Part 2 — Color System

### Primary Palette

|Token                   |Hex      |RGB          |Usage                                      |
|------------------------|---------|-------------|-------------------------------------------|
|`--crypt-black`         |`#0A0A0F`|10, 10, 15   |App background. Near-black with cool tilt. |
|`--crypt-black-elevated`|`#13131A`|19, 19, 26   |Cards, panels, elevated surfaces.          |
|`--crypt-black-deep`    |`#050507`|5, 5, 7      |Modal overlays, deepest backgrounds.       |
|`--blood-crimson`       |`#A11D2C`|161, 29, 44  |Primary accent. Wordmark. Critical actions.|
|`--blood-crimson-bright`|`#C4243A`|196, 36, 58  |Hover states for crimson elements.         |
|`--blood-crimson-muted` |`#7A1622`|122, 22, 34  |Disabled crimson elements.                 |
|`--bone-ivory`          |`#F5F1E8`|245, 241, 232|Primary text on dark. Warm off-white.      |
|`--bone-ivory-dim`      |`#C9C5BC`|201, 197, 188|Secondary text.                            |

### Secondary Palette

|Token                    |Hex      |RGB          |Usage                                          |
|-------------------------|---------|-------------|-----------------------------------------------|
|`--cathedral-gold`       |`#B8945C`|184, 148, 92 |Subtle accents, hairlines, decorative elements.|
|`--cathedral-gold-bright`|`#D4AF7A`|212, 175, 122|Hover on gold elements.                        |
|`--ash-gray`             |`#6B6B70`|107, 107, 112|Tagline text, secondary labels, muted UI.      |
|`--ash-gray-light`       |`#8A8A90`|138, 138, 144|Hover state for gray text.                     |
|`--moonlight-silver`     |`#C4C4CA`|196, 196, 202|Delicate line work, hover indicators.          |

### Semantic Palette

For functional UI states. These are *additions* to the brand colors, used only where information must be communicated.

|Token              |Hex      |Usage                                                                                               |
|-------------------|---------|----------------------------------------------------------------------------------------------------|
|`--signal-positive`|`#4A8B5C`|High-confidence prey, positive returns, completed runs. Muted forest green — not bright, never neon.|
|`--signal-warning` |`#B8945C`|Same as Cathedral Gold. Budget warnings, pending approvals.                                         |
|`--signal-danger`  |`#A11D2C`|Same as Blood Crimson. Budget exceeded, run failures.                                               |
|`--signal-neutral` |`#6B6B70`|Same as Ash Gray. Awaiting decision, no signal.                                                     |

Note: Dracul’s UI uses **only one true accent color (crimson)**. Green appears only for explicit positive signals. The semantic palette is restrained because the application is dense with information — adding more color would create visual noise.

### Color Rules

- **Never pure black or pure white.** Crypt Black is the deepest tone; Bone Ivory is the lightest.
- **Never bright or saturated red.** Blood Crimson is the only red. No alternates.
- **Gold appears in lines and small accents only** — never in fills larger than 24×24px equivalent.
- **The semantic palette is the only place green appears.** No green in branding, navigation, or atmospheric elements.
- **Hover states shift colors by one named tone.** No arbitrary opacity adjustments. Always use a named token.

-----

## Part 3 — Typography

### Type Stack

```css
--font-display: 'Cormorant Garamond', 'EB Garamond', Garamond, Georgia, serif;
--font-body: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
--font-mono: 'JetBrains Mono', 'IBM Plex Mono', Consolas, monospace;
```

All three fonts are free via Google Fonts. Self-host them in production for performance and privacy (no external Google calls).

### Type Scale

Modular scale based on 1.250 (major third), anchored at 16px base.

|Token           |Size|Line Height|Letter Spacing|Use                         |
|----------------|----|-----------|--------------|----------------------------|
|`--text-display`|64px|1.1        |-0.02em       |Hero headlines, splash pages|
|`--text-h1`     |48px|1.15       |-0.01em       |Page titles                 |
|`--text-h2`     |36px|1.2        |-0.01em       |Section headers             |
|`--text-h3`     |28px|1.3        |0             |Subsections, card titles    |
|`--text-h4`     |22px|1.35       |0             |Inline headers              |
|`--text-body-lg`|18px|1.6        |0             |Lead paragraphs, prose      |
|`--text-body`   |16px|1.6        |0             |Default body                |
|`--text-body-sm`|14px|1.5        |0             |Secondary text, captions    |
|`--text-micro`  |12px|1.4        |0.05em        |Labels, badges, metadata    |
|`--text-mono`   |14px|1.5        |0             |Code, tickers, numbers      |

### Typographic Rules

**Display & Headers (Cormorant Garamond):**

- Used for `--text-display`, `--text-h1`, `--text-h2` only
- Wordmark “DRACUL” always uppercase
- Other display headers always title case or sentence case, never all caps

**Body (Inter):**

- Used for `--text-h3` and below, all UI elements
- Always sentence case in prose
- Lowercase for taglines, navigation, micro-labels
- Uppercase only for tiny tags (`APACHE 2.0`, `ACTIVE`, `PENDING`) under 12px

**Monospace (JetBrains Mono):**

- Used for: code blocks, ticker symbols (`NVDA`, `MSFT`), prices (`$143.50`), confidence scores (`0.87`), token counts (`12,453`), all numerical data
- Never used for prose
- Slightly smaller than body text it sits next to (14px in 16px line)

### Numerals

This is critical for an investment app: **always use tabular figures for numerical data**.

```css
.tabular {
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";
}
```

Apply to: prices, percentages, token counts, run durations, dates in tables, any numerical column. Without this, columns of numbers fail to align and look amateurish.

-----

## Part 4 — Spacing & Layout

### Spacing Scale

Based on 4px increments. Vuetify’s default 4px-based scale aligns naturally.

|Token       |Value|Use                                         |
|------------|-----|--------------------------------------------|
|`--space-1` |4px  |Tight — icon padding                        |
|`--space-2` |8px  |Small — between related items               |
|`--space-3` |12px |Default — within components                 |
|`--space-4` |16px |Default — between components                |
|`--space-5` |24px |Comfortable — between sections within a card|
|`--space-6` |32px |Loose — between cards                       |
|`--space-8` |48px |Page sections                               |
|`--space-10`|64px |Major page sections                         |
|`--space-12`|96px |Page-level breathing room                   |

### Layout Principles

- **Generous negative space.** The application breathes. Density is achieved through type hierarchy and color, not through cramming.
- **12-column grid** at desktop breakpoints (Vuetify default).
- **Max content width** of 1280px on prose pages, 1600px on data dashboards.
- **Single-pane primary, two-pane secondary.** The main view is one column. Detail panels overlay or push from the right; they do not split the layout permanently.

### Breakpoints

Vuetify’s defaults work, with the proviso:

- **Mobile (xs, sm):** Dracul is desktop-first. Mobile is a degraded but functional experience — read-only views, simplified prey lists, no charts. The hunting takes place on a workstation.
- **Tablet (md):** Full functionality, simplified visualizations.
- **Desktop (lg, xl, xxl):** Primary target.

-----

## Part 5 — Components

The following components form the Dracul application vocabulary. Vuetify provides the primitives; we restyle to the brand.

### 5.1 Cards

The most-used container. Three variants:

**Default Card**

- Background: `--crypt-black-elevated`
- Border: 1px solid rgba(184, 148, 92, 0.1) — a barely-visible Cathedral Gold hairline
- Border-radius: 4px (subtle, not rounded)
- Padding: `--space-5`
- Shadow: none. Depth is communicated through color, not shadow.

**Hover state:**

- Border opacity rises to 0.3
- Subtle 100ms transition

**Active/Selected card:**

- Border: 1px solid `--cathedral-gold`
- Left edge: 2px solid `--blood-crimson` (a “selected” accent)

### 5.2 Prey Card (custom component)

The primary domain object — a single Strigoi finding. This card is the most important component in the application.

```
┌────────────────────────────────────────────────────────────┐
│ ▌ NVDA  NVIDIA Corp                            [PEAD] 🦇   │  ← header: ticker (mono),
│                                                             │    name (body), anomaly badge
│ ─── confidence ─────────────────────────                    │
│ 0.87  ████████████████████████░░░░░░  high                  │  ← confidence bar
│                                                             │
│ Earnings beat by 12%, post-announcement drift typical of    │
│ semiconductor sector with similar magnitude historically    │  ← thesis (3-4 lines)
│ correlates with 60-day continued upward drift.              │
│                                                             │
│ ─── signals ───────  ─── risks ────────                     │
│ • Q3 EPS +14% YoY    • Sector rotation risk                 │  ← signals/risks
│ • Guidance raised    • Macro headwinds                      │     (two columns)
│ • Insider buying                                            │
│                                                             │
│ discovered by strigoi-echo · 2 hours ago · horizon: 60 days │  ← metadata footer
└────────────────────────────────────────────────────────────┘
```

**Visual rules:**

- Left border (the `▌` mark): 3px solid `--blood-crimson` if confidence > 0.75, `--cathedral-gold` if 0.5–0.75, `--ash-gray` below
- Ticker symbol: `--font-mono`, `--text-body-lg`, `--bone-ivory`
- Company name: `--font-body`, `--text-body`, `--bone-ivory-dim`
- Anomaly badge: small uppercase tag in `--cathedral-gold` border with `--text-micro`
- Confidence bar: bar in `--blood-crimson`, track in `rgba(255,255,255,0.08)`
- Thesis: prose, `--bone-ivory`, restrained line length (max ~70 characters)
- Bullet markers: small `•` in `--cathedral-gold`

### 5.3 Strigoi Status Pill

Compact indicator showing each Strigoi’s current state.

```
🦇 strigoi-spin       ●  hunting       ⏱ 14m elapsed
🦇 strigoi-insider    ○  resting       ⏱ next: 22:00
🦇 strigoi-echo       ●  hunting       ⏱ 3m elapsed
🦇 strigoi-lazarus    ◐  paused        ⏱ —
🦇 strigoi-index      ○  resting       ⏱ next: tomorrow
🦇 strigoi-merger     ✕  budget hit    ⏱ resumes 00:00
```

**Status indicators:**

- `●` (filled crimson dot) — currently hunting
- `○` (hollow ash circle) — resting, scheduled
- `◐` (half circle, gold) — manually paused
- `✕` (crimson cross) — budget exceeded, blocked

### 5.4 Cost Ledger Bar

Tier-based budget visualization.

```
reasoning tier    ████████████████░░░░  80% / $2.40 of $3.00 daily
routine tier      ███░░░░░░░░░░░░░░░░░  15% / $0.45 of $3.00 daily
local tier        ░░░░░░░░░░░░░░░░░░░░   0% / no cost (Ollama)
```

- Bar color: `--blood-crimson` for >75% utilization, `--cathedral-gold` for 50–75%, `--bone-ivory-dim` below
- Numbers: `--font-mono`, tabular figures
- “no cost” label appears for local tier in Ash Gray

### 5.5 Run Trace Timeline

For inspecting individual Bee runs.

```
00:00  ▼ strigoi-spin awakened (manual trigger)
00:00      pre-screening 47 candidates
00:01      qualified: 6 candidates
00:01      llm call: anthropic/claude-sonnet-4 · 2,453 tokens
00:04      response received (3.2s)
00:04      parsed 4 prey
00:04      ▲ run completed · 4 prey · $0.012
```

- Time markers: `--font-mono`, `--text-body-sm`, `--ash-gray`
- Action verbs: `--bone-ivory`
- Critical events (▼ start, ▲ end): `--blood-crimson` for the symbol
- LLM calls: highlighted slightly with `rgba(184, 148, 92, 0.05)` background

### 5.6 Buttons

Three button variants. Vuetify’s `<v-btn>` restyled.

**Primary (Crimson)**

- Background: `--blood-crimson`
- Text: `--bone-ivory`
- Hover: `--blood-crimson-bright`
- Border-radius: 4px
- Padding: 12px 24px
- Font: Inter, 14px, medium weight, no uppercase

**Secondary (Bordered)**

- Background: transparent
- Border: 1px solid `--ash-gray`
- Text: `--bone-ivory`
- Hover: border becomes `--cathedral-gold`, text remains ivory

**Tertiary (Ghost)**

- No background, no border
- Text: `--bone-ivory-dim`
- Hover: text becomes `--bone-ivory`

**Destructive actions** (rare): use Primary style, but add a confirmation dialog. Never have a one-click destructive button.

### 5.7 Inputs & Forms

- Background: `--crypt-black-deep`
- Border: 1px solid rgba(255,255,255,0.1)
- Focus border: `--cathedral-gold`
- Text: `--bone-ivory`
- Placeholder: `--ash-gray`
- Border-radius: 4px

### 5.8 Tables

Tables are central to a financial app.

```
┌──────────┬─────────┬──────────┬─────────┬──────────┐
│ ticker   │ confid. │ strigoi  │ horizon │ found    │
├──────────┼─────────┼──────────┼─────────┼──────────┤
│ NVDA     │   0.87  │ echo     │  60d    │ 2h ago   │
│ AMD      │   0.74  │ insider  │  90d    │ 4h ago   │
│ MSFT     │   0.69  │ spin     │  180d   │ 6h ago   │
└──────────┴─────────┴──────────┴─────────┴──────────┘
```

- Header row: `--text-micro`, uppercase, `--ash-gray`, slight letter-spacing
- Body rows: `--text-body-sm`, `--bone-ivory` for ticker, `--bone-ivory-dim` for secondary cols
- Numerical columns: `--font-mono`, tabular figures, right-aligned
- Row hover: subtle `rgba(184, 148, 92, 0.05)` background
- No vertical borders, only thin horizontal `rgba(255,255,255,0.05)` rules between rows

### 5.9 Charts

For displaying historical performance, backtest results, cost over time.

- Background: transparent (or `--crypt-black-elevated` if in card)
- Grid lines: `rgba(255,255,255,0.04)` — barely visible
- Axis labels: `--text-micro`, `--ash-gray`
- Primary line: `--blood-crimson`, 2px stroke
- Secondary line: `--cathedral-gold`, 2px stroke
- Positive area fill: `rgba(74, 139, 92, 0.15)` — Signal Positive at low opacity
- Negative area fill: `rgba(161, 29, 44, 0.15)` — Crimson at low opacity
- Tooltip: `--crypt-black-deep` background, `--bone-ivory` text, no border

Recommended library: **ApexCharts** or **Chart.js** with a custom theme. Avoid heavy libraries; the dataset is not huge.

-----

## Part 6 — Interaction & Motion

### Motion Principles

- **Slow and deliberate.** Dracul is not a Slack notification. Animations are 200–400ms, ease-in-out.
- **Purposeful, never decorative.** Every animation communicates a state change.
- **Reversible.** If something can fade in, it must fade out.
- **Respect `prefers-reduced-motion`.** Always.

### Standard Transitions

```css
--transition-fast: 150ms cubic-bezier(0.4, 0, 0.2, 1);
--transition-base: 250ms cubic-bezier(0.4, 0, 0.2, 1);
--transition-slow: 400ms cubic-bezier(0.4, 0, 0.2, 1);
```

### Micro-interactions

- **Card hover:** border opacity transition, 150ms
- **Button press:** subtle scale to 0.98, 100ms, return on release
- **New prey arrives:** fade in from below, 400ms with 50ms stagger if multiple
- **Strigoi awakens:** small pulse on the status indicator, 600ms once
- **Budget warning crosses 75%:** color shift over 300ms

### What we do NOT do

- Bouncing, springing, or rubber-banding animations
- Continuous looping animations (loading spinners excepted, but use a static dot pulse rather than a spinning circle where possible)
- Particle effects, glows, or atmospheric overlays
- Page transitions that obscure content for more than 200ms

-----

## Part 7 — Iconography

### Primary Icons

Five icons form the core visual vocabulary:

- **🌙 Moon** — time, scheduled work, the cycle
- **🦇 Bat** — Strigoi, agents, autonomous workers
- **🪙 Coin** — cost, value, prey
- **📜 Scroll** — Verdict, chronicle, reports
- **🗝 Key** — auth, permissions, the lord’s authority

### Functional Icons

For UI mechanics, use a single icon set: **Phosphor Icons** (free, MIT). Style: regular weight (1.5pt strokes). Phosphor’s elegance matches the design language.

### Icon Rules

- Stroke-based, never filled
- Single weight throughout the application (1.5pt at 24px reference)
- Color: `--bone-ivory-dim` default, `--cathedral-gold` for active, `--blood-crimson` for critical
- Size: 16px, 20px, or 24px — no other sizes
- Always paired with text in primary navigation; icon-only allowed in toolbars

### Forbidden Icons

- Skulls, coffins, crosses, gravestones — too literal
- Dollar signs (`$`) as standalone icons
- Robot heads, brain icons, neural-network glyphs
- Any animated icon

-----

## Part 8 — Vuetify Theme Configuration

### Theme Definition

```js
// src/plugins/vuetify.ts
import { createVuetify } from 'vuetify'

export default createVuetify({
  theme: {
    defaultTheme: 'dracul',
    themes: {
      dracul: {
        dark: true,
        colors: {
          background: '#0A0A0F',
          surface: '#13131A',
          'surface-bright': '#1F1F28',
          'surface-light': '#2A2A35',
          'surface-variant': '#13131A',
          'on-background': '#F5F1E8',
          'on-surface': '#F5F1E8',
          primary: '#A11D2C',
          'primary-darken-1': '#7A1622',
          secondary: '#B8945C',
          'secondary-darken-1': '#9A7847',
          accent: '#B8945C',
          error: '#A11D2C',
          info: '#6B6B70',
          success: '#4A8B5C',
          warning: '#B8945C',
        },
        variables: {
          'border-color': '255, 241, 232',
          'border-opacity': 0.08,
          'high-emphasis-opacity': 1,
          'medium-emphasis-opacity': 0.78,
          'disabled-opacity': 0.4,
          'idle-opacity': 0.04,
          'hover-opacity': 0.08,
          'focus-opacity': 0.12,
          'selected-opacity': 0.12,
          'activated-opacity': 0.16,
          'pressed-opacity': 0.16,
          'dragged-opacity': 0.08,
        },
      },
    },
  },
  defaults: {
    VBtn: {
      variant: 'flat',
      rounded: 'sm',
      style: 'text-transform: none; letter-spacing: 0;',
    },
    VCard: {
      variant: 'flat',
      rounded: 'sm',
    },
    VTextField: {
      variant: 'outlined',
      density: 'comfortable',
    },
  },
})
```

### Global CSS

```css
/* src/styles/global.css */
@import url('https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@400;500;600&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap');

:root {
  /* Colors */
  --crypt-black: #0A0A0F;
  --crypt-black-elevated: #13131A;
  --crypt-black-deep: #050507;
  --blood-crimson: #A11D2C;
  --blood-crimson-bright: #C4243A;
  --blood-crimson-muted: #7A1622;
  --bone-ivory: #F5F1E8;
  --bone-ivory-dim: #C9C5BC;
  --cathedral-gold: #B8945C;
  --cathedral-gold-bright: #D4AF7A;
  --ash-gray: #6B6B70;
  --ash-gray-light: #8A8A90;
  --moonlight-silver: #C4C4CA;

  /* Semantic */
  --signal-positive: #4A8B5C;
  --signal-warning: #B8945C;
  --signal-danger: #A11D2C;

  /* Typography */
  --font-display: 'Cormorant Garamond', 'EB Garamond', Garamond, Georgia, serif;
  --font-body: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  --font-mono: 'JetBrains Mono', 'IBM Plex Mono', Consolas, monospace;

  /* Transitions */
  --transition-fast: 150ms cubic-bezier(0.4, 0, 0.2, 1);
  --transition-base: 250ms cubic-bezier(0.4, 0, 0.2, 1);
  --transition-slow: 400ms cubic-bezier(0.4, 0, 0.2, 1);
}

body {
  background-color: var(--crypt-black);
  color: var(--bone-ivory);
  font-family: var(--font-body);
  font-feature-settings: "kern", "liga";
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

.tabular {
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";
}

.font-display {
  font-family: var(--font-display);
}

.font-mono {
  font-family: var(--font-mono);
}

/* Reduced motion */
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

-----

## Part 9 — Application Layouts

### 9.1 Shell Layout

```
┌────────────────────────────────────────────────────────────────┐
│  DRACUL  ┊  chronicle · strigoi · vistierie · backtest    ⚙ ☾  │  ← top bar
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│                                                                 │
│                       MAIN CONTENT AREA                         │
│                                                                 │
│                                                                 │
│                                                                 │
├────────────────────────────────────────────────────────────────┤
│  ☾ 6 Strigoi · 2 hunting · last verdict 12m ago · $0.43 today   │  ← status bar
└────────────────────────────────────────────────────────────────┘
```

- Top bar: 64px tall, Crypt Black Elevated, wordmark left, navigation center, controls right
- Status bar: 32px tall, Crypt Black Deep, ash-gray text, ever-present operational summary
- Main content: scrollable area between bars
- Theme toggle (`☾`): rare — Dracul is dark by default, but a cream-mode exists for daylight reading of long Verdicts

### 9.2 Chronicle View (Default)

Shows Prey Cards in a chronological feed, most recent first. Filters along the right edge.

### 9.3 Strigoi Detail View

For inspecting one Strigoi: status, recent runs, cost over time, recent prey, configuration.

### 9.4 Verdict View

For reading consolidated multi-Strigoi findings on a single instrument. The most prose-heavy view; uses the wider 1280px content width.

### 9.5 Vistierie Dashboard

Cost, budgets, run history. Purely informational. Cathedral Gold appears more here than elsewhere — this is the treasury.

### 9.6 Backtest View

Charts and tables of historical Strigoi performance against actual market outcomes.

-----

## Part 10 — Voice & Microcopy

### Tone Principles

- **Confident, not boastful.** Dracul is a research tool, not an oracle.
- **Patient, not slow.** Never apologize for taking time, but never indulge in lengthy throat-clearing.
- **Atmospheric where useful, technical where required.** Marketing copy can lean gothic. Error messages don’t.
- **Romanian-rooted, English-spoken.** Names (Dracul, Vistierie, Strigoi) are Romanian; everything else is plain English.

### Microcopy Examples

**Empty states:**

- Empty Chronicle: “The Strigoi have not yet returned tonight.”
- No prey for filter: “No findings match your filter.”
- No runs yet: “The brood sleeps. Trigger a hunt to begin.”

**Loading states:**

- “Awakening the Strigoi…”
- “Reading the night’s findings…”
- “The treasury is counting…”

**Errors:**

- Standard errors: plain technical language. “Connection to API failed. Retry?” — no gothic language here.
- Critical errors: also plain. “Budget exceeded for strigoi-spin. Hunt blocked until 00:00.” — concrete and actionable, no flavor.

**Confirmations:**

- Destructive: “Permanently delete this Verdict?” — never “destroy”, “banish”, or theatrical synonyms.
- Settings save: “Saved.” — minimal.

### Pitfalls

- Never use exclamation marks
- Never use emoji in primary UI (except the small set of bat/moon/coin in headers)
- Never name LLMs to the user (“Claude”, “GPT-4”) — always “the model”, “the agent”, or specific tier (“reasoning tier”)
- Never quote prices without context (“up 12%” without timeframe is meaningless)

-----

## Part 11 — Accessibility

This is non-negotiable. Dracul is a tool you use daily — accessibility is comfort, not just compliance.

### Contrast Requirements

|Combination                  |Ratio |WCAG                                                    |
|-----------------------------|------|--------------------------------------------------------|
|Bone Ivory on Crypt Black    |14.8:1|AAA                                                     |
|Bone Ivory Dim on Crypt Black|9.2:1 |AAA                                                     |
|Blood Crimson on Crypt Black |4.8:1 |AA Large only — never use crimson on black for body text|
|Cathedral Gold on Crypt Black|5.4:1 |AA                                                      |
|Ash Gray on Crypt Black      |4.5:1 |AA Large only                                           |

### Keyboard Navigation

- All interactive elements reachable via Tab
- Focus indicators always visible: 2px solid `--cathedral-gold` outline with 2px offset
- Skip links at top of page
- Modal traps focus correctly

### Screen Readers

- All icons have `aria-label`
- Status changes announced via `aria-live="polite"`
- Charts have text-table alternatives
- Banner alt text: “Dracul — structural market anomalies, eternal patience.”

### Reduced Motion

Already covered in motion section. All animations honor the user preference.

-----

## Part 12 — Asset Inventory

|Asset               |Path                             |Purpose                        |
|--------------------|---------------------------------|-------------------------------|
|Banner (dark)       |`assets/branding/banner-dark.png`|GitHub social preview          |
|Banner SVG          |`assets/branding/banner.svg`     |Scaling, modification          |
|Wordmark            |`assets/branding/wordmark.svg`   |Inline usage                   |
|Favicon             |`public/favicon.ico`             |Tab icon — small bat silhouette|
|App icon (PWA)      |`public/app-icon-512.png`        |PWA / mobile                   |
|OG image            |`assets/branding/og-image.png`   |Social sharing meta            |
|Bat icon            |`assets/icons/bat.svg`           |UI element                     |
|Moon icon           |`assets/icons/moon.svg`          |UI accent                      |
|Coin icon           |`assets/icons/coin.svg`          |UI accent                      |
|Color palette swatch|`docs/branding/palette.png`      |Design reference               |

-----

## Part 13 — Evolution

This design system is a snapshot, not a contract. Some elements will be tested and refined:

- The six-bat motif may need adjustment if Dracul ever has more or fewer Strigoi.
- The Cathedral Gold accent may be reduced further, or eliminated entirely if it begins to feel decorative.
- The Cormorant Garamond wordmark may be replaced if a custom typographic mark is ever commissioned.
- The Chronicle layout is the most likely candidate for iteration once real usage patterns emerge.

Any change to this document should be discussed and recorded in the commit message. The brand’s strength comes from consistency, not from spontaneity.

-----

*Last updated: project inception.*
*Maintained by: the lord of the crypt.*
