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
| strigoi-insider | **implemented 2026-05-25** — Form-4 cluster screener, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases). Each clustered filer now carries its free-text Form-4 officer `role` (empty for non-officers), so the LLM's CEO/CFO-diversity rubric can actually weigh it. |
| strigoi-echo | **implemented 2026-06-02; signal upgrade (v2 SP1) 2026-06-24; market-reaction signals (v2 SP2) 2026-06-27** — Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic long-only pre-screen (current price ≥ $5). Earnings announcements come from **Finnhub `/calendar/earnings`** (primary), with the Yahoo earnings calendar demoted to a config-selectable fallback. A deterministic enrichment layer replaces the old raw-5%-surprise signal with academic PEAD signals: **time-series SUE** (Foster seasonal-random-walk, from SEC EDGAR quarterly diluted-EPS history with date-based seasonal alignment) ranked cross-sectionally into **deciles** (z-band fallback for thin batches), **revenue-surprise / double-beat**, and **consecutive seasonal beats**. SP2 further enriches surviving candidates with market-reaction signals from daily OHLC and Finnhub metrics (see SP2 section below). The LLM applies a SUE-based confidence rubric (not a fixed 5% threshold); each emitted prey echoes its numeric **SUE + decile** in `signals`. Long-only. |
| strigoi-lazarus | **implemented 2026-06-05** — watchlist-scoped; screens watchlist names within ~10% of their 52-week low with a light solvency gate (positive ROA or free cash flow, modest leverage); the reasoning-tier LLM applies Piotroski's F-Score judgement and emits `QUALITY_52W_LOW` Prey |
| strigoi-index | **implemented 2026-06-06** — Wikipedia S&P 500 main constituents table (`Date added` column), routine tier (model_purpose `routine`), agent registered with Vistierie on startup; surfaces recently-added S&P 500 constituents; the routine-tier LLM judges whether the inclusion-drift window is still open and emits `INDEX_INCLUSION` Prey |
| strigoi-merger | **implemented 2026-06-05** — EDGAR EFTS `forms=DEFM14A,SC TO-T` (definitive merger proxies + tender offers, last 45 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; surfaces recent SEC deal filings (DEFM14A definitive merger proxies + SC TO-T tender offers); the reasoning-tier LLM judges the spread and closing probability and emits `MERGER_ARB` Prey |

### Strigoi-Echo SP2: market-reaction signals

**SP2 market-reaction signals (deterministic, added 2026-06-27).** Each surviving
candidate is further enriched from daily OHLC and Finnhub metrics:

- `announcementCar1d` / `announcementCar3d` — market-adjusted abnormal return around the
  report day, computed vs the market proxy (default SPY) and beta-adjusted when beta is
  known. A positive CAR with the same sign as the surprise is the strongest confirming
  signal; a negative CAR (the market already faded the beat) is a hard counter-argument.
- `abnormalVolume` — report-day volume / trailing 20-day average volume.
- `momentum6_12m` — price return over the 6-12 month window (price + earnings momentum
  compound).
- `adv`, `marketCap`, `beta`, `sector` — liquidity and size, used to dampen confidence on
  large, heavily-arbitraged names.

Every SP2 field carries an availability flag (`carAvailable`, `metricsAvailable`); missing
OHLC or metrics degrade the affected field conservatively and never abort the run.

### Strigoi-Echo SP3: earnings-quality + event/timing gate

**SP3 earnings-quality + event/timing gate (deterministic, added 2026-06-27).** Before a
candidate reaches the LLM it must pass a server-side hard gate:

- **Sloan accrual ratio** `(netIncome − operatingCashFlow) / totalAssets` from EDGAR. Above
  `echo.gate.max-accrual-ratio` (default 0.10) the beat is accrual-driven (not cash-backed) and
  the candidate is dropped.
- **Confounder screen** over Finnhub company news since the report date (M&A, restatement,
  guidance cut, dilution, investigation). Any hit drops the candidate — the announcement-CAR is
  then not the drift signal. (EDGAR 8-K item-code parsing is a deferred refinement.)
- **Timing gate** — if the next earnings report is within `echo.gate.min-days-to-next-earnings`
  (default 10) the candidate is dropped (next-report event risk overlays the drift).

Survivors carry soft signals for the LLM: `accrualRatio`, `netEstimateRevisionsProxy`
(analyst recommendation-trend delta) / `netEstimateRevisionsDirection` (the sign of that
proxy — the analyst recommendation-revision direction, not management guidance), and
`nextEarningsDate` / `daysToNextEarnings`. All SP3 lookups degrade gracefully (availability flags) and never abort a run.

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

## Adding a new agent

Adding a new agent to Dracul requires code changes and one config entry. Registration with Vistierie is now fully DB-driven — no hardcoded registrar list.

### Code

1. **Webhook controller** — if the agent produces `Prey`, extend `HuntController`; otherwise write a bespoke `@RestController`. Secure it with a bearer token matched against the agent's token property.
2. **Adapter / screener** — deterministic pre-screen logic (no LLM). Lives in the appropriate `dracul-hunting-grounds` module or a new sub-module.
3. **Output domain + persistence** — only needed when the agent emits a new domain object (not `Prey`). Add a Flyway migration and update `documentation/architecture.md`.
4. **Prompt + schema** — add `src/main/resources/prompts/<agent-name>.md` and, if the agent returns structured JSON, a schema file `<agent-name>-schema.json` alongside it.
5. **Tool catalog** — if the agent needs tool callbacks, implement `AgentToolCatalog.catalogEntries()` contributions (or add entries via a new `AgentDefaultProvider.catalogEntries()` override).
6. **`AgentDefaultProvider` bean** — implement the interface in the agent's package, annotate with `@Component` (and `@ConditionalOnProperty` if the agent is opt-in). Return an `AgentDefinition` from `defaultDefinition()` with the correct name, prompt path, schedule, model purpose, enabled state, and tool bindings. This is the only registration step required.

### How registration works

On startup `AgentDefinitionBootstrap` iterates all `AgentDefaultProvider` beans and upserts each definition into the `agent_definition` table (insert-if-absent, so manual edits made via the REST API survive redeployment). `GenericAgentRegistrar` then reads all definitions from the DB, prepends `dracul.public-url` to the webhook callback URLs, appends the current language directive to the system prompt, and calls Vistierie's create-or-update agent endpoint. The registrar re-runs on `AgentDefinitionChangedEvent` and `LanguageChangedEvent`, so runtime edits take effect immediately without a restart.

`SettingsController.strigoiNames()` derives the budget-panel roster directly from the `AgentDefaultProvider` beans filtered to names starting with `strigoi-`; no hardcoded list is maintained anywhere.

### Config

Add one `@ConditionalOnProperty` guard (e.g. `dracul.strigoi.<name>.enabled`) and one webhook-token property (e.g. `dracul.strigoi.<name>.webhook-token`). Document both in `documentation/configuration.md`. No new `dracul.agents.*` namespace — each agent owns its own property prefix.

## Groparul (exit-timing agent)

**Implemented 2026-06-14.** Dracul's exit-timing agent. Groparul ("the gravedigger")
monitors HELD watchlist positions daily and advises when to exit — SELL, TRIM, or HOLD.
It is advisory only: it never executes trades.

Groparul runs once per day after the US close (default cron: `0 0 22 * * 1-5`, UTC).
On each run:

1. `POST /api/gropar/tools/fetch-held-positions` — tool webhook pulls all HELD watchlist items
   **across all users** with their entry price and share count. Each position carries an opaque
   `positionId` (the watchlist-item id) the LLM echoes back so signals can be routed to their owner.
2. `GroparExitIndicators` assembles the exit-indicator bundle for each position. The technical
   indicators are sourced from Agora's bundled `get_indicators` MCP tool (one call per position)
   via the `AgoraResearch` facade — Dracul no longer computes them locally:
   - **ATR Chandelier Stop** — 22-period ATR × 3.0 multiple (Chandelier Exit)
   - **MA Cross** — 50-period vs 200-period simple moving average
   - **52-week proximity** — distance to 52-week low/high
   - **Gain/loss thresholds** — unrealised gain ≥ 40% or unrealised loss ≥ 15% (derived in Dracul
     from the position's entry price and Agora's current close)
   - **Time stop** — based on position age (derived in Dracul from the verdict horizon)
   - **R-framework** — `RiskMetricsService` (retained, position-domain) is fed Agora's ATR to derive
     the frozen ATR initial stop, risk unit R, gain in R, MFE since entry, and a giveback
     (peak-drawdown) guard (`INITIAL_STOP` / `GIVEBACK` rules)
3. Indicator bundle → reasoning-tier LLM judgment. The LLM returns `ExitSignal` per position:
   `verdict` (SELL / TRIM / HOLD), `thesis_status` (INTACT / WEAKENING / INVALIDATED / NONE —
   `NONE` means the position has no original thesis, e.g. a manually-added position, so gropar
   judges it on technical indicators alone), `rationale` (German), and `confidence`.
4. `POST /api/gropar/complete` — completion webhook persists each signal to `dracul.exit_signals`
   (V11), scoped to the owner resolved from its `position_id`; signals with an unknown id are
   skipped. `GET /api/exit-signals` then serves each user only their own signals.
5. A Telegram push fires for SELL/TRIM signals on the single operator channel, with the owner
   email prefixed into the alert text.

Groparul is the first agent built end-to-end via the **generic add-an-agent recipe**: it uses
`AgentDefaultProvider` (`GroparDefaults` bean) for DB-driven registration and a bespoke
`GroparWebhookController` (bearer-token auth, `@ConditionalOnProperty`). No custom registrar.

**Position guard.** Groparul only does useful work over held positions, so Dracul auto-pauses
it at Vistierie whenever the held-position count across **all** users is zero and unpauses it as
soon as any user holds a position — Vistierie skips a paused agent's cron, so empty runs never
fire. The guard is
driven by watchlist changes (`WatchlistController` publishes a `WatchlistChangedEvent` after every
mutation) and reconciled once at startup; see `GroparPauseReconciler`. Groparul's pause is therefore
**system-managed**: turn the agent on or off via its `dracul.gropar.enabled` flag, not the manual
pause toggle (which the guard would overwrite on the next watchlist change).

gropar also surfaces a scale-out ladder (`profitTargets` = [+2R, +4R] with
`scaleOutFractions`) and an overextension indicator (`distToMa200InAtr`) that flags a
wide distance above the MA200 as a mean-reversion „TRIM in die Stärke" hint.

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

1. `POST /api/daywalker/events` runs deterministic detection across **all users'**
   watchlists (no LLM) and returns trigger events. Each trigger type is evaluated
   **once per distinct ticker** — price/volume spikes, negative news, insider sells,
   and analyst downgrades are market-wide signals, so a single market-data fetch per
   ticker serves every user who watches it.
2. Vistierie spawns one reasoning-tier (Sonnet) child run per triggered symbol; the
   run judges severity and returns `{severity, thesis, confidence}`.
3. `POST /api/daywalker/complete` fans out the assessment to **every owner** of that
   symbol — one `dracul.daywalker_alerts` row is written per owner whose
   `(owner, symbol, trigger_type)` cooldown has not yet elapsed.

For HELD positions the Daywalker now judges each event against gropar's pre-set exit
levels rather than abstract percentages: every trigger is fanned out per HELD position
and carries `active_stop`, `next_target`, and `atr` plus a deterministic `breached_level`
(STOP/TARGET). A level breach defaults to CRITICAL severity; the LLM may downgrade only
with a stated reason. Watch-only tickers remain generic, purely technical assessments.

CRITICAL alerts also fire a best-effort Telegram push (configurable via
`DRACUL_DAYWALKER_NOTIFY_LEVEL`); the push fires **once per symbol event** on the
single operator channel (not once per owner). The delivery outcome is recorded in
`daywalker_alerts.notification_sent`.

New alerts also stream live to the Chronicle frontend over SSE (`GET /api/events`,
`alert.new`), surfaced in the live-alert panel. The SSE event fires **once per
symbol event** on the global live stream, not per owner.

A per-`(owner, symbol, trigger_type)` cooldown (default 60 min) keeps a sustained
condition from generating repeat rows for the same owner on every poll.

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
