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
| strigoi-spin | not yet implemented |
| strigoi-insider | **implemented 2026-05-25** — Form-4 cluster screener, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases) |
| strigoi-echo | not yet implemented |
| strigoi-lazarus | not yet implemented |
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

Not a scheduled agent — a `StreamingBee` that runs during US market
hours (15:30–22:00 MEZ):

1. Polls prices and volume every 5 minutes for all active watchlist items.
2. Subscribes to news and SEC filing streams.
3. On trigger (price spike > 3%, negative news, Form-4 SELL, analyst
   downgrade, volume spike > 3×):
   - **Haiku pre-filter**: "is this actually relevant?"
   - If relevant → **Sonnet full assessment**
   - If critical → Telegram push + `notification_sent = true`
   - Always → persist row in `dracul.daywalker_alerts`

### Tier routing

| Stage | Tier | Model |
|---|---|---|
| Pre-filter | routine | claude-haiku-4-5 |
| Full assessment | reasoning | claude-sonnet-4-6 |
| Critical escalation | reasoning | claude-opus-4-7 (rare) |

### Trigger types

| TriggerType | Threshold |
|---|---|
| PRICE_SPIKE | > 3% in 1 hour |
| NEGATIVE_NEWS | news-adapter classification |
| INSIDER_SELL | Form-4 SELL filing |
| ANALYST_DOWNGRADE | news/calendar adapter |
| VOLUME_SPIKE | > 3× rolling average |
