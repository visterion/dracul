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
| strigoi-spin | **implemented 2026-06-05; term-sheet enrichment 2026-07-08** — EDGAR Form-10-12B spin-off registrations (last 60 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; deterministic pre-screen surfaces recent spin-co registrations, the LLM assesses the Greenblatt forced-selling thesis (only tradeable tickers persisted). Each candidate now carries `termSheet` / `termSheetAvailable` — the filing's cleaned summary-term-sheet text via Agora's `get_filing_text` tool (`AgoraFilings.filingText(filingUrl)`); the LLM extracts parent/ratio/record-date/size from it, fail-soft (conservative judgement) when unavailable |
| strigoi-insider | **implemented 2026-05-25** — Form-4 cluster screener, Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic pre-screen (≥3 distinct filers, 30-day window, total > $500k purchases). Each clustered filer now carries its free-text Form-4 officer `role` (empty for non-officers), so the LLM's CEO/CFO-diversity rubric can actually weigh it. `fetch_recent_clusters` also annotates each cluster with `netInsiderDollar` (purchases minus concurrent insider sales in the window) and `concurrentInsiderSells` (distinct selling insiders); this is advisory only — the LLM weighs it into confidence, no cluster is dropped for it. |
| strigoi-echo | **implemented 2026-06-02; signal upgrade (v2 SP1) 2026-06-24; market-reaction signals (v2 SP2) 2026-06-27** — Haiku tier (model_purpose `routine`), agent registered with Vistierie on Dracul startup, deterministic long-only pre-screen (current price ≥ $5). Earnings announcements come from **Finnhub `/calendar/earnings`** (primary), with the Yahoo earnings calendar demoted to a config-selectable fallback. A deterministic enrichment layer replaces the old raw-5%-surprise signal with academic PEAD signals: **time-series SUE** (Foster seasonal-random-walk, from SEC EDGAR quarterly diluted-EPS history with date-based seasonal alignment) ranked cross-sectionally into **deciles** (z-band fallback for thin batches), **revenue-surprise / double-beat**, and **consecutive seasonal beats**. SP2 further enriches surviving candidates with market-reaction signals from daily OHLC and Finnhub metrics (see SP2 section below). The LLM applies a SUE-based confidence rubric (not a fixed 5% threshold); each emitted prey echoes its numeric **SUE + decile** in `signals`. Long-only. |
| strigoi-lazarus | **implemented 2026-06-05; real Piotroski F-score (Slice 2b) 2026-07-07** — watchlist-scoped; screens watchlist names within ~10% of their 52-week low with a light solvency gate (positive ROA or free cash flow, modest leverage), plus a **cheapness (valuation) gate** (must be cheap by price-to-book or price/FCF-per-share) and a hard drop on high Sloan accruals — both deterministic and applied server-side. The F-Score is no longer judged qualitatively by the LLM: it is computed deterministically via Agora's `get_fundamental_score` tool (strict scoring + a `fScoreCriteriaAvailable` coverage count from SEC companyfacts) and attached to each surviving candidate. The reasoning-tier LLM then applies the ranking/confidence rubric — rank by `fScore`, skip below 6, dampen confidence when `fScoreCriteriaAvailable` is thin — and narrates the thesis, rather than scoring the F-Score itself, and emits `QUALITY_52W_LOW` Prey. |
| strigoi-index | **implemented 2026-06-06** — Wikipedia S&P 500 main constituents table (`Date added` column), routine tier (model_purpose `routine`), agent registered with Vistierie on startup; surfaces recently-added S&P 500 constituents; the routine-tier LLM judges whether the inclusion-drift window is still open and emits `INDEX_INCLUSION` Prey |
| strigoi-merger | **implemented 2026-06-05; term-sheet enrichment 2026-07-08** — EDGAR EFTS `forms=DEFM14A,SC TO-T` (definitive merger proxies + tender offers, last 45 days), reasoning tier (model_purpose `reasoning`), agent registered with Vistierie on startup; surfaces recent SEC deal filings (DEFM14A definitive merger proxies + SC TO-T tender offers); the reasoning-tier LLM judges the spread and closing probability and emits `MERGER_ARB` Prey. Each candidate now carries `termSheet` / `termSheetAvailable` — the filing's cleaned summary-term-sheet text via Agora's `get_filing_text` tool (`AgoraFilings.filingText(filingUrl)`) — plus `lastPrice` / `priceAvailable`; the LLM extracts offer/consideration/conditions/termination-fee from the term sheet and computes the spread vs `lastPrice`, fail-soft (conservative judgement) when unavailable |

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

Each candidate also carries `analystCoverage` (analyst count from the latest recommendation
trend) and `coverageAvailable`. Low coverage marks an under-followed name where PEAD drift
tends to be stronger and persist longer — the prompt treats it as a mild neglect-premium
up-weight (high coverage is a mild dampener); it is advisory only, the LLM decides.

## Hunt Pattern

Every Strigoi follows the same three-step shape:

1. **Pre-screen** (deterministic, no LLM) — pulls candidates from the
   appropriate hunting-ground adapter (EDGAR, prices, news, calendar)
   and filters to the ones worth spending tokens on.

2. **LLM evaluation** via Vistierie — a Sonnet-tier (`reasoning`) or
   Haiku-tier (`routine`) call. The prompt includes any `ACTIVE` patterns
   from the Pattern Library that apply to this Strigoi. Returns structured
   `Prey` JSON, including:
   - `kill_criteria` (1–5 strings, required): falsifiable exit conditions — a measurable
     threshold, a concrete date, or a single unambiguous public event under which the
     thesis is dead. They flow through the Prey→ExecutorSignal adapter; the executor
     hard-rejects (`SCHEMA_INVALID`) any entry signal without them. Vague concerns belong
     in `risks`.

3. **Persist** — the parsed `Prey` records are written to `dracul.prey`.
   Vistierie handles cost accounting and run history; Dracul handles
   domain persistence.

   When the executor is enabled (`dracul.executor.enabled=true`), the same
   `/complete` request also auto-feeds the executor: `PreySignalEmitter` maps
   each persisted prey to a pending `executor_signal` (skipping symbols already
   open or already pending). This is a read-only-to-execution handoff — the
   hunters still only produce prey; the code-guarded executor is the sole agent
   that acts on the resulting signals. See `hunting-grounds.md`
   ("Prey → ExecutorSignal flow"). With the executor disabled, hunts complete
   exactly as before.

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
   When the position has an originating verdict, its `thesis` block also carries `killCriteria` —
   the deduplicated union of `killCriteria` across the verdict's contributing prey (via
   `VerdictRepository.contributingPreyIdsById` + `PreyRepository.findByIds`), so the LLM can judge
   whether a hold's original invalidation conditions have since been met. The key is omitted when
   there are none, or fail-soft (logged, omitted) if the lookup fails.
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

### Consensus annotation (payoff families)

When the Voievod's `fetch_consensus_clusters` tool builds a cluster (symbols
flagged by ≥2 distinct Strigoi), Dracul deterministically annotates it with a
Dracul-domain payoff taxonomy — this is investment vocabulary, never Agora
market data. Each prey's `anomalyType` is classified into a `payoffFamily`:
**DRIFT** (PEAD, quality-at-52w-low, insider clusters, spin-offs — open-ended
upside, gradual repricing, ~3–12 months) or **EVENT** (merger-arb,
index-inclusion — capped payoff, cliff downside, short and event-terminated),
falling back to **UNKNOWN** for unrecognised anomaly types. At the cluster
level, Dracul also derives `crossFamily` (true when the contributing prey span
more than one payoff family — a strong warning that the underlying theses
imply incompatible price paths) and `discoverySpreadDays` (days between the
earliest and latest `discoveredAt` in the cluster, a temporal-coherence hint
for "is this the same episode?").

**This annotation is advisory only — it never drops a cluster.** Every
detected cluster is still surfaced to the Voievod's LLM call; Java only
attaches the `payoffFamily` / `crossFamily` / `payoffFamilies` /
`discoverySpreadDays` fields as extra signal. The endorse-or-drop decision
remains entirely the LLM's.

The Voievod's system prompt applies this signal through an ordered
endorse-logic, evaluated gate by gate with the first failing gate dropping
the cluster: (1) payoff/horizon compatibility — a `crossFamily` cluster is
treated as contradictory and dropped unless a genuinely rare reinforcing
reason can be named; (2) temporal coherence — a large `discoverySpreadDays`
weakens the case that the signals describe the same episode; (3) independent
mechanism — the agreeing Strigoi must reinforce each other for different
reasons, or the agreement is redundant and dropped; (4) hunter reliability —
insider and lazarus findings are structurally robust, while index-inclusion
and merger-arb are heavily arbitraged, so agreement among only arbitraged
hunters is kept but the summary language is dampened; (5) compounding risks —
if the prey's `risks[]` confirm the same downside twice, that is grounds to
drop despite bullish agreement. The prompt is **default-skeptical**: a shared
ticker across hunters is treated as coincidence (multiple-testing / FDR
concern) until a concrete independent mechanism is named, and an empty
verdict list is treated as a valid, respectable outcome.

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

## Executor (guarded broker-execution agent, slices 1+2)

**Implemented (slices 1+2).** Unlike the six Strigoi, the Executor is not a
hunter — it does not scan hunting grounds or emit Prey. It is a **guarded
execution agent** that now manages the full position lifecycle: it consumes
signals (advice from Strigoi, gropar, or a human operator, injected via
`POST /api/executor/signals`) and decides whether to enter, and it reviews
open positions to decide whether to exit on a soft trigger. It is
**venue-neutral**: the prompt and tool set carry no notion of paper vs
live — `dracul.executor.connection` is an operator/config choice the agent
cannot see or influence, and the code guards below apply identically
regardless of connection.

### Entries

The signal is advice, never a command. Every signal is re-evaluated
independently by code before the LLM even gets to reason about it, and the
LLM's own request to enter a position is itself re-checked before any broker
call is made:

- **`VetoService`** (pure, deterministic, no I/O) evaluates every signal
  against three checks before the agent may act on it: `SCHEMA_INVALID`
  (missing symbol/direction/confidence), `LOW_CONFIDENCE` (below
  `dracul.executor.min-confidence`), `MAX_POSITIONS` (open-position count at
  or above `dracul.executor.max-positions`).
- **`OrderGuard`** (pure, deterministic) is the final check on the LLM's own
  `place_entry` request: it requires a valid protective stop on the correct
  side of the reference price, a strictly positive quantity, and that the
  order targets the configured connection.
- **`place-entry` runs signal → veto → guard → broker in code.** The LLM
  cannot place an order directly — it can only call the `place_entry` tool,
  which either forwards to the broker after every check passes or returns a
  structured rejection reason. See `documentation/api.md` for the full
  reason list.

Research reads (`get_quote` / `get_indicators` for ATR/swing levels) go
through the existing read-only `AgoraClient`, the same one Strigoi and
gropar use; broker writes go through Agora's webhook trading tools
(`AgoraTrading`), scoped to whichever connection/token the operator
configured.

### Exits (slice 2)

Exits are split between code, which owns everything hard and mechanical, and
the LLM, which owns only the soft judgment call. Every call to
`fetch-open-positions` first runs, server-side, in order:

1. **`ReconcileService`** — syncs broker fills against `executor_position`,
   retires positions the broker reports closed, applies `cooldown`.
2. **`HardTriggerService`** — force-closes a position on stop-breach or
   giveback (fraction of peak MFE-in-R given back, active once MFE clears
   `dracul.executor.giveback-active-from-r`) — always enforced, never the
   LLM's call.
3. **`StopRatchetService`** — ratchets the active stop up to the chandelier
   level (`dracul.executor.chandelier-mult` × ATR below the highest price
   reached), never down.

Only after that does the LLM see the (now current) open positions, each
carrying a `soft_trigger` block (`chandelier_breach`, `ma_break`,
`confirm_count`, `kill_criteria_breached`). Once a soft trigger has held for
`dracul.executor.soft-confirm-min` consecutive runs, the LLM is expected to
call `exit_position(symbol, reason, confidence, reasoning)`. Unlike
`place_entry`, exits carry **no veto/order-guard gate** — they are always
permitted, since closing a position is never something code needs to guard
against.

`kill_criteria_breached` is populated by `KillCriteriaEvaluator`
(`de.visterion.dracul.criteria`), a deterministic, stateless, best-effort
parser that recognizes only absolute **price-level** kill criteria (e.g.
"Close below $90.00", "Price rises above 120") and evaluates them against
the position's daily close. It is v1-scoped: percent thresholds (e.g.
"widens above 12%") and qualitative criteria (e.g. "Merger terminated") are
left unparsed — those stay in the raw `kill_criteria` list for the LLM to
judge itself. Anything the evaluator can't confidently parse is silently
skipped rather than raising an error, so a malformed or free-form criterion
never blocks the pipeline.

Every decision point (entry, hard exit, stop-ratchet, soft exit) writes a
`decision_log` row tagged with the active `dracul.executor.rule-version`,
giving a single, richer audit trail across the whole lifecycle (see
`documentation/architecture.md` for the table shapes).

### Scope

The injection seam (`POST /api/executor/signals`) is still the only way
signals reach the executor — there is no automatic wiring from a Strigoi's
Prey or gropar's exit signal into the executor's queue. `RejectReason`
declares `MAX_TRANCHE`, and it is now enforced (was declared-only): the
`add-tranche` tool rejects with `MAX_TRANCHE` (writing a `decision_log`
entry, same as the other reject paths) once a position's `tranche` count
reaches `dracul.executor.max-tranche` (default 2), so tranching beyond the
configured cap is blocked before any eligibility/sizing work runs.
`VetoService` also now enforces `CORRELATED`: an entry is rejected when an
open position already exists in the same sector (case-insensitive) with the
same `mechanism` (anomaly type) as the candidate signal — this blocks
doubling up on one anomaly within a sector even below the `CONCENTRATION`
cap; a null sector or mechanism passes (fail-soft). The fuller veto catalog
(kill-criteria monitoring) remains out of scope and lands in later slices.

See `documentation/architecture.md` for the doctrine note on why guarded
execution is the one deliberate exception to Dracul's read-only design, and
`documentation/configuration.md` for the full `dracul.executor.*` property
reference.
