# Strigoi

A Strigoi is a specialised hunter agent. Each Strigoi targets exactly
one documented market anomaly. Strigoi are registered with Vistierie as
scheduled agents; Vistierie owns the runtime, Dracul owns the domain
logic and the hunt pattern.

## Roster

| # | Name | Anomaly | Tier | Academic source |
|---|------|---------|------|-----------------|
| 1 | strigoi-spin | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | strigoi-insider | Insider cluster buys | routine | Lakonishok & Lee 2001 |
| 3 | strigoi-echo | Post-earnings drift (PEAD) | routine | Bernard & Thomas 1989 |
| 4 | strigoi-lazarus | Quality at 52w low | reasoning | Piotroski 2000 |
| 5 | strigoi-index | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | strigoi-merger | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |

## Implementation status

| Strigoi | Status |
|---|---|
| strigoi-spin | **implemented 2026-06-05** — EDGAR Form-10-12B spin-off registrations (last 60 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; deterministic pre-screen surfaces recent spin-co registrations, the LLM assesses the Greenblatt forced-selling thesis (only tradeable tickers persisted) |
| strigoi-insider | **implemented 2026-05-25** — Form-4 cluster screener, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases) |
| strigoi-echo | **implemented 2026-06-02** — Yahoo earnings-calendar adapter, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic long-only pre-screen (positive surprise ≥ 5%, current price ≥ $5) |
| strigoi-lazarus | **implemented 2026-06-05** — watchlist-scoped; screens watchlist names within ~10% of their 52-week low with a light solvency gate (positive ROA or free cash flow, modest leverage); the reasoning-tier LLM applies Piotroski's F-Score judgement and emits `QUALITY_52W_LOW` Prey |
| strigoi-index | not yet implemented |
| strigoi-merger | not yet implemented |

## Hunt Pattern

Every Strigoi follows the same three-step shape:

1. **Pre-screen** (deterministic, no LLM) — pulls candidates from the
   appropriate hunting-ground adapter (EDGAR, prices, news, calendar)
   and filters to the ones worth spending tokens on.

2. **LLM evaluation** via Vistierie — a Sonnet-tier (`reasoning`) or
   Haiku-tier (`routine`) call. The prompt includes any `ACTIVE` patterns
   from the Pattern Library that apply to this Strigoi. Returns structured
   `Prey` JSON.

3. **Persist** — the parsed `Prey` records are written to `dracul.prey`.
   Vistierie handles cost accounting and run history; Dracul handles
   domain persistence.

### Reference implementation

```java
@Component
public class StrigoiSpin implements Bee<HuntRequest, List<Prey>> {

    private final EdgarClient edgar;
    private final SpinoffScreener screener;
    private final PatternLibrary patterns;   // active Voievod lessons

    @Override
    public BeeId id() { return BeeId.of("strigoi-spin"); }

    @Override
    public AgentTier preferredTier() { return AgentTier.REASONING; }

    @Override
    public List<Prey> hunt(HuntRequest input, BeeContext ctx) {
        // Step 1: deterministic pre-screen
        var candidates = edgar.findRecentForm10Filings(input.lookback());
        var qualified  = screener.filter(candidates);
        if (qualified.isEmpty()) return List.of();

        // Step 2: LLM with pattern context
        var activePatterns = patterns.activePatternsFor(this.id());
        var response = ctx.llm().complete(
            buildEvaluationPrompt(qualified, activePatterns));

        // Step 3: parse and return
        return PreyParser.parse(response, qualified);
    }
}
```

## Voievod (weekly reviewer)

Not a hunter — the referee after the battle. The Voievod runs every
Sunday evening:

1. Fetches all `prey` rows whose `time_horizon` has elapsed and that
   have not yet been outcome-assessed.
2. Compares the thesis against the actual price return over the horizon.
3. Writes outcome columns (`outcome_actual_return`,
   `outcome_thesis_validated`) back into `dracul.prey`.
4. Aggregates per-Strigoi hit-rate, average return, and sub-patterns.
5. Fires a single Opus (`reasoning`) LLM call: "What do we learn?"
6. Writes proposed `Pattern` rows with status `PENDING`.

**Patterns only activate when the user approves them** in the Pattern
Library view. Approved patterns are injected into the next Strigoi run
as additional prompt context, closing the feedback loop.

## Daywalker (streaming guardian)

**Implemented 2026-06-04** as a Vistierie `StreamingBee` consumer (Daywalker
sub-project 2 of 4). Not a scheduled agent — Vistierie opens a window-bounded
session at market open and polls Dracul's event-source webhook every 5 minutes.

1. `POST /api/daywalker/events` runs deterministic detection over the active
   watchlist (no LLM) and returns trigger events.
2. Vistierie spawns one reasoning-tier (Sonnet) child run per event; the run
   judges severity and returns `{severity, thesis, confidence}`.
3. `POST /api/daywalker/complete` persists each assessment to
   `dracul.daywalker_alerts`.

CRITICAL alerts also fire a best-effort Telegram push (configurable via
`DRACUL_DAYWALKER_NOTIFY_LEVEL`); the delivery outcome is recorded in
`daywalker_alerts.notification_sent`.

New alerts also stream live to the Chronicle frontend over SSE (`GET /api/events`,
`alert.new`), surfaced in the live-alert panel.

A per-`(symbol, trigger_type)` cooldown (default 60 min) keeps a sustained
condition from spawning a run on every poll.

### Trigger types (v1)

| TriggerType | Source | Deterministic condition |
|---|---|---|
| PRICE_SPIKE | Yahoo intraday (5-min) | abs(price change) > 3% over ~1h |
| VOLUME_SPIKE | Yahoo intraday (5-min) | volume > 3× rolling average |
| INSIDER_SELL | EDGAR Form-4 | a Form-4 sale ("S") for the symbol |
| NEGATIVE_NEWS | Finnhub company-news | a new material headline (LLM judges negativity) |
| ANALYST_DOWNGRADE | Finnhub recommendation-trend | rating trend shifts toward sell |

### Tier routing (v1)

A single reasoning-tier (Sonnet) assessment per event. The documented
Haiku pre-filter and Opus critical escalation are deferred; deterministic
detection plus the cooldown already gate event volume.
