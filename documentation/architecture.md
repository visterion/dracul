# Architecture

Dracul is a Spring Boot 4 service plus a Vue 3 / Vuetify 3 frontend
(Chronicle). It consumes [Vistierie](https://github.com/visterion/vistierie)
as its agent runtime. Strigoi, Voievod, and Daywalker are Vistierie
agents; Vistierie owns scheduling, tier-based model routing, cost
tracking, and the kill switch. Dracul owns domain logic, market-data
adapters, persistence, synthesis, and the frontend.

## Residents of the Crypt

```
                      ┌─────────────────┐
                      │     DRACUL      │  Lord (orchestrator, Spring service)
                      └────────┬────────┘
                               │
          ┌────────────────────┼────────────────────┬──────────────┐
          │                    │                    │              │
   ┌──────▼──────┐      ┌─────▼──────┐      ┌─────▼──────┐   ┌───▼────────┐
   │   STRIGOI   │      │  VOIEVOD   │      │ DAYWALKER  │   │ RENFIELD   │
   │  6 hunters  │      │ 1 reviewer │      │ 1 guardian │   │ 1 analyst  │
   │  scheduled  │      │  scheduled │      │ streaming  │   │ scheduled  │
   │  nightly    │      │  daily     │      │ mkt hours  │   │ daily      │
   └─────────────┘      └─────────────┘     └─────────────┘   └────────────┘
    hunts prey           reviews outcomes    guards watchlist  reviews holdings
```

## Module Layout

```
dracul/
├── dracul-domain/              # Pure Java 25 domain model
│   ├── instrument/             # Instrument, AssetClass, Venue
│   ├── prey/                   # Prey, Signal, Risk, Thesis
│   ├── verdict/                # Verdict (consolidated consensus)
│   ├── pattern/                # Pattern, PatternStatus
│   ├── alert/                  # DaywalkerAlert, AlertSeverity
│   ├── watchlist/              # WatchlistItem, WatchReason
│   └── anomaly/                # AnomalyType sealed interface
│
├── dracul-strigoi/             # Hunter implementations
│   ├── strigoi-spin/
│   ├── strigoi-insider/
│   ├── strigoi-echo/
│   ├── strigoi-lazarus/
│   ├── strigoi-index/
│   └── strigoi-merger/
│
├── dracul-voievod/             # Outcome reviewer
│   └── outcome-analyzer, pattern-extractor, lesson-proposer
│
├── dracul-daywalker/           # Streaming market guardian
│   └── union (depot ∪ watchlist), trigger-detectors, alert-pipeline
│
├── dracul-synthesizer/         # Verdict generation
│   └── consensus-detector, narrative-generator
│
├── dracul-hunting-grounds/     # Market-data adapters
│   ├── edgar-adapter/          # SEC EDGAR (Form 10, 4, 8-K)
│   ├── agora-market-data/      # prices / OHLC via Agora (MCP: get_quote/get_ohlc)
│   ├── news-adapter/           # Finnhub / NewsAPI
│   └── calendar-adapter/       # Earnings, index reconstitutions
│
├── dracul-crypt/               # Postgres persistence (Flyway)
│   ├── prey-archive/
│   ├── verdict-store/
│   ├── pattern-library/
│   ├── watchlist-store/
│   ├── alert-log/
│   └── flyway-migrations/
│
├── dracul-notifications/       # Messenger integration
│   ├── telegram-bot/
│   └── webhook-dispatcher/
│
├── dracul-app/                 # Spring Boot wiring
│   ├── config/
│   ├── api/                    # REST controllers
│   ├── auth/                   # Single-user token (Phase 1)
│   ├── scheduler/              # Bee schedule triggers
│   └── observability/
│
└── chronicle/                  # Vue 3 / Vuetify 3 frontend (8 views)
```

## Domain Model

```java
public record Instrument(
    InstrumentId id, String symbol, AssetClass assetClass,
    Venue venue, Currency currency
) {}

// Output of one Strigoi run
public record Prey(
    PreyId id,
    InstrumentId instrumentId,
    StrigoiId discoveredBy,
    AnomalyType anomalyType,
    double confidence,
    String thesis,
    List<Signal> signals,
    List<Risk> risks,
    List<String> killCriteria,    // 1-5 falsifiable exit conditions (V19)
    Horizon timeHorizon,
    Instant discoveredAt,
    UserId discoveredFor          // Phase 1: always "default"
) {}

// Consolidated finding (multiple Strigoi agree)
public record Verdict(
    VerdictId id,
    InstrumentId instrumentId,
    List<PreyId> contributingPrey,
    double consensusScore,
    String summary,
    Instant createdAt,
    UserId createdFor
) {}

// Voievod-proposed heuristic
public record Pattern(
    PatternId id,
    StrigoiId appliesTo,
    String patternName,
    String description,
    int evidenceCount,
    double confidence,
    PatternStatus status,         // PENDING, ACTIVE, REJECTED
    Instant proposedAt,
    Instant activatedAt,
    UserId proposedFor
) {}

// Daywalker-generated alert
public record DaywalkerAlert(
    AlertId id,
    InstrumentId instrumentId,
    TriggerType triggerType,
    Map<String, Object> triggerData,
    Severity severity,
    String llmAssessment,
    Instant triggeredAt,
    boolean notificationSent,
    UserId belongsTo
) {}

public record WatchlistItem(
    WatchlistItemId id,
    InstrumentId instrumentId,
    WatchReason reason,           // VERDICT_TRACKING, HELD_POSITION
    Optional<Position> position,
    Instant addedAt,
    UserId belongsTo
) {}
```

## Database Schema (`dracul` schema)

Key tables — see Flyway migrations in `dracul-crypt/` for authoritative DDL.

| Table | Purpose |
|---|---|
| `prey` | Immutable Strigoi findings; includes outcome columns filled by Voievod. Unique index `uq_prey_natural_day` (V21) on `(symbol, anomaly_type, discovered_by, user_id, discovered_at::date)` makes hunt `/complete` webhooks idempotent — a retried delivery for the same hunter/day is skipped (`ON CONFLICT DO NOTHING`) instead of duplicated, and `HuntController` emits downstream effects only for the actually-inserted subset. |
| `verdicts` | Consolidated multi-Strigoi consensus records |
| `verdict_notes` | Append-only audit trail per verdict (id, verdict_id FK, body, created_at, user_id) |
| `patterns` | Voievod-proposed heuristics (`PENDING` → `ACTIVE` / `REJECTED`) |
| `pattern_evidence` | Supporting cases per pattern (symbol, anomaly, outcome, return); `pattern_id` FK ON DELETE CASCADE |
| `watchlist_items` | Items the Daywalker monitors; `active` generated column |
| `daywalker_alerts` | Every Daywalker trigger, with LLM assessment and notification flag |

**Verdict columns added:**
- `decision` (TEXT, nullable) — CHECK constraint: TRACK, INTERESTING, DISMISS, ACTED
- `decided_at` (TIMESTAMPTZ, nullable) — timestamp of decision

**Watchlist price refresh:**
`WatchlistPriceRefresher` is a `@Scheduled` bean (enabled via `SchedulingConfig` /
`@EnableScheduling`). It runs every minute during US market hours (default cron:
`0 * 13-20 * * MON-FRI`, UTC; configurable via
`dracul.watchlist.price-refresh.cron`). On each tick it calls
`WatchlistPriceRepository.distinctTickers()`, fetches live quotes from **Agora**
(`get_quote` over MCP, via `AgoraMarketData`), and writes `current_price` /
`day_change_percent` back via `updatePriceByTicker()`. The refresh is
independent of read traffic: `GET /api/watchlist` serves the stored values
directly without triggering any market-data call.

**Watchlist indexes:**
- `uq_watchlist_user_ticker` (UNIQUE) — composite index on (user_id, ticker) for idempotent POST

**Daywalker-alert columns (V4):**
- `symbol`, `trigger_type`, `thesis`, `severity`, `vistierie_run_id` (TEXT, nullable)
- `confidence` (NUMERIC(4,3), nullable)
- `created_at` (TIMESTAMPTZ, default now()) — drives the per-`(symbol, trigger_type)` cooldown
- index `idx_daywalker_alerts_symbol_trigger` on (user_id, symbol, trigger_type, created_at DESC)

**Daywalker-alert columns (V5):**
- `notification_sent` (BOOLEAN, default false) — true when a Telegram push was delivered for this alert

**Daywalker-alert columns (V34):**
- `event_type` (VARCHAR(32), nullable) — the LLM-confirmed news event category from the shared
  `NewsEventType` taxonomy (`NewsEventType.wireValue()`): `earnings_miss`, `guidance_cut`, `ma`,
  `dilution`, `restatement`, `investigation`, `macro`, or `"other"` when the LLM reports a
  NEGATIVE_NEWS assessment that doesn't map to a taxonomy type. Written only by
  `DaywalkerCompletionService`/`DaywalkerAlertRepository` from the daywalker completion webhook's
  extended assessment schema (v1.1.0) — no backfill, no other write path. `daywalker-deep`'s
  schema is not extended with `event_type`, so its escalation completion always passes `null`
  here; `DaywalkerAlertRepository`'s upsert SQL uses `event_type = COALESCE(:et, event_type)` so
  a same-day deep-escalation update never nulls out a value the original assessment persisted.
  `NewsDetector` (the deterministic NEGATIVE_NEWS trigger source) tags each candidate headline
  with `NewsEventTagger` and rides the matched types along as a comma-separated `event_tags` hint
  in the trigger's detail map for the LLM; only headlines that tag at least one type reach the
  LLM as a NEGATIVE_NEWS trigger — untagged headlines are suppressed before any child run (logged
  at INFO: `news: {} untagged headlines suppressed for {}`).

**Daywalker-alert columns (V30):**
- `watchlist_item_id` is now nullable (FK kept, enforced only for non-null values) — depot-sourced
  alerts (`DaywalkerEventEngine` fans triggers over `HeldPositionService` positions, A6) carry no
  watchlist row at all; `DaywalkerCompletionService` persists these keyed by `symbol` alone, routed
  to the single `dracul.primary-user-email` owner (same convention as gropar's `exit_signals`).

**Trade proposals table (V35):**
- `trade_proposals` — one row per Renfield proposal per run (runs daily at 12:00 UTC). Schema:
  `id` (UUID PK), `owner` (VARCHAR NOT NULL), `symbol` (VARCHAR NOT NULL), `action` (VARCHAR(32) NOT NULL),
  `entry_zone` (TEXT NULL), `stop` (TEXT NULL), `confidence` (NUMERIC NULL), `rationale` (TEXT NOT NULL),
  `market_note` (TEXT NULL), `run_id` (VARCHAR NOT NULL), `created_at` (TIMESTAMPTZ NOT NULL DEFAULT now()),
  with a UNIQUE constraint on `(run_id, symbol)` — one proposal per (run, symbol) pair, enforced via
  `ON CONFLICT DO NOTHING` (idempotent insert) so retried webhook deliveries never duplicate. One bundled
  Telegram push per run (sent from `RenfieldWebhookController`); SSE publishes a single bundled
  `proposal.new` event per completed run (`{count, run_id, ts}`), not one event per proposal row. An
  empty watchlist means the scheduler never triggers a run at all (no Telegram, no SSE); a completed
  run whose proposals list comes back empty still inserts zero rows but sends a "keine Vorschläge
  heute" Telegram digest (no SSE event in that case).

**Verdict columns (V6):**
- `contributing_prey_ids` (JSONB, NOT NULL DEFAULT '[]') — array of prey UUIDs the verdict was synthesized from; written by the Voievod synthesizer on every upsert. Used for change-detection (skip upsert when the cluster is identical) and will feed outcome analysis in Etappe 8.

**Watchlist position columns (V8):**
- `entry_price` (NUMERIC(12,4), nullable) — operator-recorded entry price; NULL until a position is recorded
- `share_count` (NUMERIC(12,4), nullable) — operator-recorded share count (fractional shares allowed); NULL until set
- Both are surfaced on the read API as `entryPrice` / `shareCount` and set via `PATCH /api/watchlist/{id}/position`; client-side P&L is derived from `currentPrice − entryPrice`.

**Pattern evidence table (V9):**
- `pattern_evidence` — one row per supporting case backing a pattern: `id` (UUID PK), `pattern_id` (UUID NOT NULL, FK → `patterns(id)` ON DELETE CASCADE), `symbol`, `company_name`, `anomaly_type` (TEXT), `occurred_at` (TIMESTAMPTZ), `supported` (BOOLEAN), `return_percent` (NUMERIC(7,2), nullable), `note` (TEXT, nullable)
- index `idx_pattern_evidence_pattern` on `pattern_id`
- Surfaced via `GET /api/patterns/{id}/cases` (cases ordered by `occurred_at` DESC); seeded with evidence for the 3 pending patterns.

**Learning loop — accepted patterns feed back into hunts:** approving a pattern
(`PATCH /api/patterns/{id}` with `action: "approve"`) sets `status = 'ACTIVE'`.
`PatternRepository.findAcceptedByStrigoi(strigoi)` selects `statement` for every
`ACTIVE` row where `applies_to_strigoi` equals that strigoi or `'all'`; every
`HuntController` subclass's fetch-tool response includes the result as
`active_patterns` (field-injected `ObjectProvider<PatternRepository>`, absent-bean
safe). Voievod's fetch tool includes `active_patterns` too, but sourced from
`PatternRepository.findAllAccepted()` (every `ACTIVE` pattern, unscoped) since it
reviews cross-hunter consensus clusters. Both hunter and Voievod prompts instruct
the agent to weigh candidates against these lessons. Caveat: `HuntController`
responses ride `ToolFetchCache`, so a pattern approved/rejected after a tool's
cache entry was populated is only reflected in `active_patterns` once that entry's
TTL expires. See `strigoi.md` ("Learning loop") for the full write-up.

**Exit signals table (V11):**
- `exit_signals` — one row per gropar verdict per position per run: `id` (UUID PK), `symbol` (TEXT NOT NULL), `verdict` (TEXT NOT NULL, CHECK: SELL / TRIM / HOLD), `rationale` (TEXT), `confidence` (NUMERIC(4,3)), `vistierie_run_id` (TEXT), `created_at` (TIMESTAMPTZ NOT NULL DEFAULT now()), `user_id` (TEXT NOT NULL DEFAULT 'default'). Partial unique index `uq_exit_signals_run_item` (V21) on `(vistierie_run_id, watchlist_item_id)` (where both are non-null) enforces at most one exit signal per run per position; a second partial unique index `(vistierie_run_id, symbol)` (V29, where `vistierie_run_id IS NOT NULL`) is the one that actually fires for gropar's depot-sourced signals since A5 -- they never carry a `watchlist_item_id` (always written `null`), so V21's index never matches them.
- index on `(user_id, symbol, created_at DESC)`
- `MorningReportService.build` (A8, 2026-07-13) reads the latest signal **per symbol**, not per `watchlist_item_id`, for the same reason: gropar's depot-sourced signals key by symbol only.
- Gropar data flow (repointed 2026-07-13 to the depot-as-SSOT model): open **depot-1** positions joined by symbol to `position_context` (`HeldPositionService.openPositions`) → daily OHLC history (Agora `get_ohlc`, via `AgoraMarketData.dailyOhlcHistory`) for the current close, plus exit TA (ATR/Chandelier stop, MA cross, 52-week proximity) from Agora `get_indicators` via `AgoraResearch` → `GroparExitIndicators` assembles the bundle (adds gain/loss thresholds; `RiskMetricsService` retained, fed Agora ATR + the position's stored context `initialStop` for the R-framework) → reasoning-tier LLM judgment → `ExitSignal` (SELL / TRIM / HOLD) → `dracul.exit_signals` → `GET /api/exit-signals` + Telegram push for SELL/TRIM verdicts. (The local `ExitIndicatorService` was removed once Agora's `get_indicators` became the TA source.) A position with no open `position_context` row degrades to TA-only (thesis/kill-criteria absent) rather than being dropped.
- Gropar position guard: `GroparPauseReconciler` (present only when `dracul.gropar.enabled=true`, `@Order(30)` so it runs after `GenericAgentRegistrar`) listens to `WatchlistChangedEvent` and to `ApplicationReadyEvent`, checks whether the live depot (`HeldPositionService.openPositions("depot-1")`) has any open positions, and calls `VistierieClient.patchAgent("gropar", positions.isEmpty())`. An in-memory last-applied state suppresses redundant Vistierie calls; a failed patch is logged and retried on the next event. gropar's pause is thus system-managed (operator uses the `enabled` flag, not the manual pause toggle).

**Verdict native currency (V13):**
- `verdicts.currency` (TEXT, nullable) — ISO 4217 code of the native currency in which `current_price` was recorded (e.g. `"USD"`). Added by `V13__verdict_currency.sql`. Conversion to the operator's display currency happens at read time in `VerdictController` via `VerdictCurrencyMapper`, mirroring the watchlist path's `WatchlistCurrencyMapper`. When `currency` equals the display currency no conversion is applied and the native fields in the API response are null.

**Watchlist position columns (V14):**
- `entry_date` (DATE, nullable, default `CURRENT_DATE`) — real purchase date, backfilled to `added_at` for existing rows; surfaced as `entryDate` on the read/write API.
- `initial_stop` (NUMERIC(12,4), nullable) — frozen native-currency ATR stop computed at entry (`entry_price − initial_stop_atr_multiple × ATR22`); used by the Groparul R-framework to derive the risk unit R and the giveback guard.

**Watchlist risk-snapshot columns (V15):**

Four nullable columns added to `watchlist_items`, originally written by gropar's `fetch_held_positions` tool call and read by the morning report. **The writer and all readers are gone as of 2026-07-13**: gropar was repointed to the depot-1 ⨝ `position_context` model (A5) and no longer writes these columns; `GET /api/morning-report` (A8), `StopProximityWatcher` (A7), and `DaywalkerEventEngine` (A6) were each repointed the same way and no longer read them — all three now read live depot positions via `HeldPositionService.openPositions("depot-1")` joined to `position_context` for stop data. The columns themselves are left in place as **orphaned/legacy** (no writer, no reader) rather than dropped, since removing them is a separate migration decision:

- `active_stop` (NUMERIC(12,4), nullable) — active trailing stop = `max(initial_stop, chandelier)`
- `next_target_2r` (NUMERIC(12,4), nullable) — 2R price target (`entryPrice + 2 × initialRisk`)
- `current_close` (NUMERIC(12,4), nullable) — last close price at the time of the gropar run
- `risk_snapshot_at` (TIMESTAMPTZ, nullable) — timestamp of the most recent gropar snapshot write

**Watchlist ATR column (V16):**

- `atr` (NUMERIC(12,4), nullable) — Average True Range (ATR22) of the position at the time of the most recent gropar run. Formerly written by gropar's `fetch_held_positions` tool call alongside the V15 snapshot columns and read by `StopProximityWatcher` to derive the stop-proximity zone width. **Orphaned as of 2026-07-13 (A7):** gropar no longer writes it (repointed to `position_context`, A5) and `StopProximityWatcher` no longer reads it — `position_context` carries no ATR, so the watcher passes `BigDecimal.ZERO` in its place (see below). The column is left in place, unwritten and unread.

**Stop-proximity watcher (`StopProximityWatcher`):**

A deterministic, intraday `@Scheduled` cron (no LLM, no Vistierie agent) in package `de.visterion.dracul.stopguard`. Disabled by default (`dracul.stopguard.enabled=false`). When enabled it fires every ~15 minutes during the US session (`0 */15 9-16 * * 1-5`, zone America/New_York). **Repointed to the depot (A7, 2026-07-13):** it no longer reads the V15/V16 `watchlist_items` snapshot; it loads live depot-1 positions via `HeldPositionService.openPositions("depot-1")` joined to `position_context`, keeps only positions with a non-null `active_stop`, batch-fetches live prices from Agora (`get_quote` via `AgoraMarketData`), and classifies each position via `StopZoneEvaluator`. Since `position_context` carries no ATR, the watcher passes `BigDecimal.ZERO` as the ATR: a zero-width band still classifies `price ≤ active_stop` as `STOP_BREACHED`, but the `STOP_PROXIMITY` warning band collapses to nothing until ATR is added to the context model:

| Condition | Zone | Severity |
|---|---|---|
| `price ≤ active_stop` | `STOP_BREACHED` | CRITICAL |
| `active_stop < price ≤ active_stop + atr-multiple × ATR` (ATR = 0 ⇒ band is empty) | `STOP_PROXIMITY` | WARNING |
| above proximity band, or stop null | no alert | — |

`StopAlertEmitter` persists qualifying alerts to the `daywalker_alerts` store keyed by `(owner, symbol, trigger_type)` — depot-sourced alerts carry no `watchlist_item_id` (nullable since V30) — broadcasts `alert.new` over SSE to the live panel, and sends a German-language Telegram push. Re-alert cooldown is per `(owner, symbol, zone)` (default ≈ 23 h); `STOP_PROXIMITY` and `STOP_BREACHED` have independent cooldowns so a price breach triggers an immediate escalation alert even if a proximity alert was already sent today.

**Agent definition tables (V10):**

Two new tables under the `dracul` schema hold runtime-editable agent definitions:

- `agent_definition` — one row per agent: `name` (TEXT PK), `model_purpose` (TEXT NOT NULL), `prompt_text` (TEXT NOT NULL), `output_schema` (JSONB NOT NULL), `schedule` (TEXT), `max_turns` (INT NOT NULL), `max_run_seconds` (INT NOT NULL), `completion_path` (TEXT NOT NULL), `event_source_path` (TEXT), `session_duration_seconds` (INT), `poll_interval_seconds` (INT), `enabled` (BOOLEAN NOT NULL DEFAULT TRUE), `updated_at` (TIMESTAMPTZ NOT NULL DEFAULT now())
- `agent_tool_binding` — one row per tool binding: `agent_name` (TEXT NOT NULL, FK → `agent_definition(name)` ON DELETE CASCADE), `tool_name` (TEXT NOT NULL), `description` (TEXT), `default_params` (JSONB), `ordinal` (INT NOT NULL DEFAULT 0), PK `(agent_name, tool_name)`; index `idx_agent_tool_binding_agent` on `agent_name`

**Agent bootstrap and registration flow:**

1. `AgentDefinitionBootstrap` (runs at startup, `@EventListener(ApplicationReadyEvent)`) iterates all `AgentDefaultProvider` beans and upserts each default into `agent_definition` + `agent_tool_binding` using insert-if-absent semantics. Rows that already exist (from a previous deploy or runtime edit via the REST API) are not overwritten, so manual customisations survive redeploys.
2. `GenericAgentRegistrar` reads all agent definitions from the DB, builds a `CreateAgentRequest` for each, prepends `dracul.public-url` to all webhook callback paths, appends the current language directive to the system prompt, and registers or updates the agent with Vistierie. It re-runs whenever an `AgentDefinitionChangedEvent` or `LanguageChangedEvent` is published (e.g. after a `PUT /api/settings/agents/{name}/definition` or `PUT /api/settings/language`), making definition changes effective immediately without a restart.

**R3 client contract (completion_webhook):** Vistierie's `POST /agents/{name}/run` endpoint does
not fall back to the agent definition's registered completion webhook — unlike its cron and
streaming dispatch paths. So any Dracul-triggered run whose result must reach back to Dracul
(e.g. `RenfieldScheduler`'s daily trigger) must pass `completion_webhook` +
`completion_webhook_token` explicitly in the run body. `VistierieClient.triggerRun` carries these
as parameters for exactly this reason (see its javadoc). The webhook URL is prepended with
`dracul.public-url` by `GenericAgentRegistrar`. Without passing them on the triggered run, the
completion is lost.

All tables include a `user_id TEXT NOT NULL DEFAULT 'default'` column for
Phase-2 multi-user readiness, **except** the Executor tables below
(V17 + V18), which are scoped to the single operator's book, not per-user.
Schema changes require a Flyway migration and an update to this document.

**Executor tables (V17 + V18):** tables backing the guarded broker-execution
agent (see `documentation/strigoi.md` for the agent's role). Like every
other table documented in this section, they live in Postgres's default
`public` schema — "`dracul` schema" throughout this document is an informal
label for tables Dracul owns, not a Postgres `CREATE SCHEMA dracul`.

| Table | Purpose |
|---|---|
| `executor_signal` | Injected advice awaiting evaluation (PK `signal_id`, caller-supplied or generated UUID); `status` transitions `PENDING` → `ACCEPTED` / `REJECTED` / `SKIPPED` |
| `executor_position` | The position book — one row per placed entry (`id` identity PK, connection, symbol, side, qty, entry/stop prices, tranche, kill criteria, source signal, status). V18 adds the exit-lifecycle columns: `highest_price`, `mfe_r` (max favorable excursion, in R), `soft_confirm_count` (consecutive-run soft-trigger streak), `exit_price`, `realized_r`, `exit_reason`, `closed_at`, `stop_order_id`. V20 adds entry-completeness columns: `sector` (candidate sector at entry time, from the Agora company-profile lookup, used for the `CONCENTRATION` veto), `entry_day_high` (the entry day's high bar, one input to tranche-2's `NEW_HIGH` eligibility), `tranche2_order_id`/`tranche2_stop_order_id` (the second bracket's broker order ids, so the stop ratchet moves *both* legs once a tranche 2 exists). V23 adds `trim_count` (scale-out ladder leg count) and `lowest_price` (adverse-excursion extreme for MAE tracking). V33 adds the book-equals-broker audit trail: `submitted_limit_price` (the limit originally submitted, persisting across `entry_price` drift), `pending_exit_reason` / `exit_order_id` / `exit_submitted_at` / `exit_price_source` / `pending_exit_fill_price` (soft-exit or hard-exit order awaiting broker confirmation; `entry_price` syncs to the broker's `avgEntryPrice` on fill, and a partial unique index `uq_executor_position_open (connection, lower(symbol)) WHERE status='OPEN'` ensures at most one OPEN row per (connection, symbol)) |
| `executor_decision` | Append-only audit trail (slice 1) — one row per signal the executor processed, whether accepted or rejected, with the veto trace and rationale (`id` identity PK) |
| `decision_log` | Append-only audit trail (V18, slice 2) — one row per *any* executor decision point (entry, hard exit, stop-ratchet, soft exit): `run_id`, `rule_version`, `trigger_type`, `symbol`, `inputs_snapshot`/`veto_results`/`order_json` (JSONB), `action`, `reason_code`, `reasoning`, `confidence_in_decision`. Richer and broader in scope than `executor_decision`, which only covers the entry path |
| `rule_versions` | (V18) One row per tagged rule-version (`dracul.executor.rule-version`, e.g. `exec-v0.2`): `valid_from`, `changes`, `prompt_hash`, `params` — makes prompt/threshold changes traceable against `decision_log.rule_version` |
| `cooldown` | (V18) Symbols temporarily excluded from fresh entries after any exit (hard or soft): `symbol`, `reason`, `expires_at` (`dracul.executor.cooldown-days` out), `exception_condition` |
| `outcome_log` | (V23) One row per `decision_log` entry/reject signal, written exclusively by the nightly `OutcomeBatchJob` — the Executor itself never reads or writes this table. `kind` is `TRADE` (a closed position's realized outcome: quantity-weighted `realized_r` across partial exits, `mae_r`/`mfe_r`, `slippage_vs_limit`, whipsaw flags `reentry_within_10d`/`roundtrip_under_5d`) or `COUNTERFACTUAL` (a rejected signal's "what would have happened", via `HypotheticalREngine`: `hypothetical` JSONB with `r_after_20d`/`r_after_60d`/`would_have_stopped_out`/`skipped_reason`, plus `hunter_label` — the triple-barrier +1R-before-1R label used for hunter-confidence calibration). Unique on `log_id_ref` (the source `decision_log.log_id`), upserted idempotently (`ON CONFLICT ... DO UPDATE`) so re-runs refine rather than duplicate a row. See "Outcome batch job" below |

**Doctrine note:** Dracul is deliberately, otherwise strictly, read-only —
no order routing, no broker integration, no auto-trading (see `README.md`
"Project values"). The Executor agent is the one intentional exception, and
it is narrowly scoped and code-guarded. As of slice 2 it manages the full
position lifecycle, not just entries:

- **Entries:** every entry the LLM requests is re-checked in code
  (`VetoService` + `OrderGuard`) before it reaches the broker — the LLM
  proposes, code disposes. A rejection returns `placed:false` with a reason.
- **Exits:** the exit lifecycle runs server-side, ahead of anything the LLM
  does, every time `fetch-open-positions` is called — `ReconcileService`
  (sync fills, retire closed positions, apply cooldown), `HardTriggerService`
  (stop-breach / giveback — always enforced, never the LLM's call), and
  `StopRatchetService` (ratchet the active stop up to the chandelier level).
  The LLM's only exit responsibility is the soft judgment: deciding to call
  `exit-position` once a `soft_trigger` (chandelier breach / MA break) has
  been confirmed for `dracul.executor.soft-confirm-min` consecutive runs.
  Unlike entries, exits carry no veto/order-guard gate — they are always
  permitted.
- **Venue-neutral by design:** the agent (prompt + tool set) has no
  awareness of which connection (`dracul.executor.connection`) it trades on
  and cannot distinguish paper from live — that choice, and the guardrails
  around it (trading-token scoping, connection allowlisting), live entirely
  in operator config, not in agent logic. The code guards above (veto,
  order guard, hard exits) are always on regardless of connection.

This exception does not weaken the doctrine for any other agent: the six
Strigoi, Voievod, gropar, and Daywalker remain strictly read-only.

**Exit lifecycle and book-equals-broker reconciliation (V33).** The executor
guarantees book-equals-broker: once a position fills, `entry_price` is the
source of truth from the broker's `avgEntryPrice` (average entry fill price
across all tranches), and `submitted_limit_price` (added in V33) keeps the
original limit for slippage accounting (`slippage = entry_price −
submitted_limit_price`). When a hard-trigger flatten (stop-breach, kill
criteria) or LLM soft exit calls the broker to close the position, the
pending-exit columns (`pending_exit_reason`, `exit_order_id`,
`exit_submitted_at`, `pending_exit_fill_price`) stamp the request and the
position stays OPEN until the broker confirms. Finalization is gated on a
two-condition check: the broker must report the position gone (no open
holdings) *and* the submitted exit order must no longer be WORKING or
PARTIALLY_FILLED. This strict gate prevents booking a wrong exit price while
the broker still holds shares and the exit order is in-flight (the verified
PSMT incident). Once both conditions are met, `ReconcileService.
finalizePendingExitOrKeep()` closes the position with the exit price (in
precedence order): the filled exit leg's `avgFillPrice` (source `FILL`) →
the fill price stamped at exit submit (`pending_exit_fill_price`, source
`FILL`) → the position's `activeStop` as a last resort (source `MARK`, no
fill data available). A partial unique index `uq_executor_position_open
(connection, lower(symbol)) WHERE status='OPEN'` enforces at most one OPEN
position per (connection, symbol), guaranteeing no duplicate booking. If a
pending exit never confirms after `dracul.executor.pending-exit-stale-hours`
(config default 24, env `DRACUL_EXECUTOR_PENDING_EXIT_STALE_HOURS`), the
position escalates once per symbol as `PENDING_EXIT_STALE` (decision log +
Telegram CRITICAL alert) — no auto-retry, no auto-close (operator-in-the-loop
spec requirement).

**RECONCILE_GONE books real broker fills (A-2b).** When a position vanishes
from the broker between two reconcile cycles with no filled exit leg observed
in this pass (e.g. a bracket fills and stops out entirely between cycles — the
verified ISRG incident, 2026-07-17), `ReconcileService.closePosition()` calls
`ExecutionGateway.closedPositions(connection)` (Agora's `get_closed_positions`)
to look up the real fill before falling back to an estimate. On a match
(preferring `clientRef` equal to the position's source signal id, else same
`symbol`) with both `openPrice` and `closePrice` present and strictly
positive, it syncs `entry_price` to the real open fill
(`positionRepo.syncEntryPrice`), books `exit_price` as the real close fill,
and persists with `exit_price_source = 'FILL'` — `realizedR` is computed from
the real entry and exit. If the gateway call fails, returns no match, or
returns a malformed match (non-positive/null price — e.g. an upstream
field-mapping bug), the position instead closes with the pre-existing
`activeStop` estimate and `exit_price_source = 'RECONCILE_GONE'`, clearly
labeling it as an estimate rather than a real fill (never `NULL`, never
`FILL`) so the outcome/calibration loop can distinguish real bookings from
placeholders. `exit_price_source` values are therefore `FILL`, `MARK`, or
`RECONCILE_GONE`.

**Hunters feed the executor (`PreySignalEmitter`).** When the executor is
enabled, each hunter's `/complete` webhook — right after it persists prey —
maps those findings to pending `executor_signal` rows via the deterministic
`PreySignalMapper`/`PreySignalEmitter` adapter (package
`de.visterion.dracul.executor`). The adapter skips symbols already open or
already pending and is wired as an optional
`ObjectProvider<PreySignalEmitter>`, so a disabled executor leaves hunts
untouched. This does not make the hunters themselves execute anything — they
still only produce prey; the executor remains the sole code-guarded agent that
acts on the resulting signals.

**`UNKNOWN_VERSION` intake gate.** Before `PreySignalEmitter` inserts a
mapped `executor_signal`, it checks the signal's `agent_version` against
`PromptRegistry.knownHashes()` (the bundled `prompts/prompt_registry.json`
body hashes for all agents) and, as a fallback, `AgentVersionResolver
.versionFor(source)` (the emitting agent's live DB-stored prompt hash — this
keeps operator-edited prompts working even though they're no longer in the
static registry). `"operator"`-sourced (manual) signals are exempt, since
they carry no prompt hash. A signal that matches neither is dropped with a
WARN log and never reaches `signalRepo.insert(...)` — it produces no
`executor_signal` row and no audit trail, ensuring a signal from an
unversioned or foreign prompt never reaches the executor.

**Prey kill-criteria column (V19):**
- `kill_criteria` (JSONB, NOT NULL DEFAULT '[]') — 1-5 hunter-emitted falsifiable exit conditions (a measurable threshold, a concrete date, or a single unambiguous public event under which the thesis is dead), carried onto `executor_signal` by the Prey→ExecutorSignal adapter (`PreySignalMapper`).

**Executor-signal thesis column (V31):**
- `executor_signal.thesis` (JSONB, nullable) — the triggering Prey's thesis snapshot (summary, entry signals, risks, anomaly type, kill criteria), carried by the Prey→ExecutorSignal adapter so it can flow into `position_context.thesis_snapshot` when the signal is filled. Nullable: `"operator"`-sourced (manual) signals and pre-existing rows stay thesis-less. When an executor entry fills for a signal whose Prey carried no narrative thesis (only `kill_criteria`), the position's `position_context.thesis_snapshot` is populated with a **kill-only** block (`killCriteria` present, no `summary`/entry signals) rather than left null — gropar treats that block as an authoritative falsifiable-exit set even without a narrative. A later reconcile pass (`PositionReconciler`, daily) upgrades the row to the full verdict-sourced thesis once a matching verdict exists for the symbol (`updateVerdictLink`).

**Verdict kill-criteria breach columns (V22):**
- `verdicts.kill_criteria_breached` (JSONB, NOT NULL DEFAULT '[]') — the **cumulative** set of contributing prey's kill criteria that have ever evaluated as breached, maintained by `VerdictKillCriteriaWatcher`. Kill criteria are falsifiable thesis-death conditions: once breached, the thesis is dead — a later price recovery never un-breaches (removes) a criterion. Breaches never clear automatically.
- `verdicts.kill_criteria_checked_at` (TIMESTAMPTZ, nullable) — when the watcher last evaluated this verdict; bumped on every poll (including when nothing is breached), so it also doubles as a liveness signal for the watcher itself.
- `VerdictKillCriteriaWatcher` (`dracul.verdict-killwatch.enabled`, default `true`; cron `dracul.verdict-killwatch.cron`, default `0 30 21 * * 1-5` UTC — after US close, before gropar) is a deterministic, no-LLM `@Scheduled` job: for every open verdict (`decision IS NULL OR decision <> 'DISMISS'`) whose symbol is **not** held *by that verdict's owner* (held-skip is owner-scoped — user A holding a symbol never suppresses user B's verdict on it; same `isHeld` semantics as gropar — tag `HELD` + `entryPrice` + `shareCount` all non-null), it batches `AgoraMarketData.quotes(...)` for the watched symbols, unions the contributing prey's `kill_criteria` (`PreyRepository.findByIds`), runs them through `KillCriteriaEvaluator.breached(...)` (the same deterministic price-threshold parser executor's soft trigger uses), and persists the union of the previously-persisted breaches plus any freshly breached criteria via `VerdictRepository.markKillCriteriaBreached`. Only *newly* breached criteria (not already in `kill_criteria_breached`) publish a `VerdictKillCriteriaBreachedEvent`, bridged onto SSE as `verdict.kill_criteria_breached` (see `documentation/api.md`) and rendered as a `KILL: <criterion>` badge on `VerdictDetailView` — the cumulative persistence also means a price that dips, recovers, and dips again can never fire a duplicate event for the same criterion. The whole poll body — and each per-verdict check — is wrapped in try/catch so one bad quote/prey lookup never blocks the rest or throws out of the scheduled method.

**Entry completeness (V20): `EntryContextAssembler` as the single I/O
layer.** Every entry decision (`place-entry`) and every tranche-2 add
(`add-tranche`) is preceded by one call into `EntryContextAssembler`, which
is the *only* place in the entry path that talks to Agora, the broker
gateway, `FxService`, or the executor repositories. Downstream decision
logic (`VetoService`, `PositionSizer`, `Tranche2Detector`) is pure — it only
ever consumes the assembled `EntryContext` record, never performs I/O
itself. Conceptually:

```
ExecutorSignal (or, for add-tranche, just a symbol)
        │
        ▼
EntryContextAssembler.assemble() / .assembleForSymbol()
        │  ├─ Agora get_indicators  → price, ATR, swing_low, ADV20-notional, day_high
        │  ├─ Agora get_company_profile → sector
        │  ├─ FxService             → instrument→account FX multiplier
        │  ├─ ExecutionGateway      → account snapshot (cash, buying power)
        │  └─ position/cooldown/signal repos → open positions, active cooldowns,
        │                                       pending-signal queue, mechanism map,
        │                                       entries-this-week, signal age
        ▼
EntryContext (assembled record; `missing` lists any absent MANDATORY datum)
        │
        ▼
VetoService.evaluate() ──(pass)──▶ PositionSizer.size() ──▶ OrderGuard.check() ──▶ AgoraTrading
        │ (fail)
        ▼
executor_decision audit row, signal REJECTED
```

`EntryContext.missing()` is the mechanism behind the `DATA_UNAVAILABLE`
pre-veto below: some fields (`swing_low`, `day_high`) are optional and may
be `null` without being flagged; every other datum that's absent because an
upstream call failed is both null/default *and* named in `missing`.

**The 15-veto catalog (`VetoService`, code-enforced, pure/deterministic —
the LLM's judgment never overrides these), preceded by a `DATA_UNAVAILABLE`
pre-veto:**

| # | Veto | Short form |
|---|---|---|
| — | `DATA_UNAVAILABLE` | Pre-veto: mandatory `EntryContext` data missing ⇒ reject before any of the 15, audited, never trade blind |
| 1 | `SCHEMA_INVALID` | Signal missing symbol/direction/confidence/kill-criteria/mechanism/agent-version |
| 2 | `LOW_CONFIDENCE` | Confidence below `dracul.executor.min-confidence` (0.65) |
| 3 | `COOLDOWN` | Active cooldown on the symbol — hard block in v1, no fresh-setup exception (origin mechanism not stored) |
| 4 | `MAX_POSITIONS` | Open-position count at cap |
| 5 | `BUDGET` | Tranche doesn't fit remaining cash / budget headroom |
| 6 | `HEAT_LIMIT` | Open heat + new risk exceeds `heat-pct` of total budget |
| 7 | `CONCENTRATION` | Sector already at `max-per-sector` open positions |
| 8 | `CORRELATED` | Same sector *and* same mechanism as an existing open position (blocks piling into one correlated bet even under the sector cap) |
| 9 | `CONTRADICTION` | `MERGER_ARB` vs. a drift-style mechanism (`PEAD`/`SPINOFF`/`INSIDER_CLUSTER`/`INDEX_INCLUSION`/`QUALITY_52W_LOW`) on the same symbol, either direction |
| 10 | `REDUNDANCY` | Same mechanism already open on the symbol |
| 11 | `LIQUIDITY` | Price below `min-price`, or ADV20 notional below `adv-multiple` × tranche |
| 12 | `SIGNAL_EXPIRED` | Signal age exceeds `max-signal-age-days` |
| 13 | `CHASED_AWAY` | Price moved beyond `chase-atr-mult` × ATR past the signal's reference price |
| 14 | `BELOW_ANCHOR` | Effective order price is on the invalidating side of the signal's reference-price anchor — drift mechanisms (`PEAD`/`INDEX_INCLUSION`) use a tight `drift-anchor-atr-mult` (default `0.0`×ATR) band, value mechanisms use a wide `value-anchor-atr-mult` (default `3.0`×ATR) band |
| 15 | `PACE_LIMIT` | New entries this ISO week at `pace-per-week` |

`VetoService.evaluate` always runs and traces all 15 checks (`veto_trace`
in the `place-entry` response), even after the first failure, except
where a check is itself gated on schema validity — see the source comments
in `VetoService` for the exact PASS/FAIL trace semantics of a
schema-invalid signal. `TRANCHE_TOO_SMALL` (sizer produced zero
shares) is a code-enforced rejection but sits outside this catalog — it is
checked by `ExecutorWebhookController` only after all 15 vetos pass,
since sizing depends on the order price the LLM/context supplies. `MAX_TRANCHE`
(tranche count already at `dracul.executor.max-tranche` for the symbol) is
likewise a code-enforced rejection checked separately, inside `add_tranche`
rather than `place-entry`'s veto pipeline.

**DATA_UNAVAILABLE semantics:** the executor's guiding principle is "when
in doubt, fail" — a missing account snapshot, price, ATR, ADV20 notional,
sector, or (for `place-entry` only) signal reference/age never falls back
to a stale or default value. Any one of them missing at assembly time
short-circuits straight to `DATA_UNAVAILABLE`, before any of the 15 vetos
even run, and is recorded as an audited rejection (`executor_decision` row,
`missing` fields joined into the reject detail) rather than a silent
skip or a guessed trade.

**Outcome batch job (V23, `OutcomeBatchJob`):** a deterministic, code-only
nightly batch — no LLM calls — that fills `outcome_log`. It runs after the
executor's evening cycle (`dracul.outcome.cron`, default `0 30 22 * * 2-6`
UTC), gated by `dracul.outcome.enabled` (default on) *and*
`dracul.executor.enabled` (default off; without the executor's `decision_log`/
`executor_position` beans wired, the job has nothing to read). A batch
failure never breaks the app — the whole run and every per-item step are
wrapped so one bad symbol/position never aborts the rest.

- **TRADE rows** — one per closed position, joined to its `ENTER`
  `decision_log` row via `signal_id` (falling back to a symbol +
  nearest-timestamp match when `signal_id` is null; a position with no
  resolvable `ENTER` row is skipped with a `WARN`, never a fabricated join).
  `realized_r` is quantity-weighted across every `TRIM` decision-log leg plus
  the final exit leg (falls back to the position's own final-leg
  `realized_r` when any leg's quantity/price is missing, rather than
  fabricating a weighted figure from incomplete data). `mae_r` derives from
  `lowest_price` (BUY) / `highest_price` (SELL); `holding_days` and
  `reentry_within_10d` use calendar-day approximations (documented in
  `configuration.md`) since neither `entry_date`/`closed_at` nor
  `decision_log.created_at` carry trading-calendar awareness. A TRADE row
  stays `complete=false` until 14 calendar days (the documented
  10-trading-day approximation) after `closed_at` have passed — re-entries
  land on days 1..14 AFTER the close, so nightly re-runs keep recomputing
  the whipsaw flags via the idempotent upsert until the window elapses,
  then the row flips `complete=true` and is skipped for good.
- **COUNTERFACTUAL rows** — one per `REJECT` `decision_log` row
  (`trigger_type=SIGNAL`) without a complete outcome yet. Walks
  `HypotheticalREngine` from the reject's `order_price`/`atr` (from
  `inputs_snapshot`) with `swing_low` always null (not captured in the
  reject snapshot — the counterfactual anchor is ATR-only), against daily
  bars fetched from the decision date forward (the same Agora OHLC adapter
  `IndexEnrichmentService` uses). The horizon is the rejected signal's own
  horizon (resolved via `signal_id`, converted calendar→trading days) or 60
  trading days as a default. Missing `order_price`/`atr`, an unresolvable
  side (no matching `executor_signal`), or an unparseable decision date all
  populate `hypothetical.skipped_reason` and mark the row `complete=true` —
  never a fabricated number. A genuine OHLC-provider outage is *not* treated
  as a permanent skip: the row is left untouched (absent/incomplete) so the
  next nightly run retries. `complete` otherwise flips to `true` once 60
  bars exist; a re-run before then re-walks and upserts the same row
  (`ON CONFLICT (log_id_ref) DO UPDATE`) rather than duplicating it.

**Spin-off lifecycle table (V26): `spin_candidate`.** Backs strigoi-spin's full
lifecycle persistence (see `documentation/strigoi.md`, "Strigoi-Spin: lifecycle
persistence"), turning the hunter from a stateless single-shot scan into a tracker
that follows each Form-10-12B registration across hunts. One row per tracked
spin-co; `SpinCandidateRepository` is JdbcClient-based (explicit `INSERT … ON
CONFLICT DO NOTHING` + guarded compare-and-set UPDATEs), mirroring `PreyRepository`
— no Spring Data JPA.

| Group | Columns |
|---|---|
| Filing identity | `cik` (spin-co registrant CIK, parsed from the filing URL), `symbol`, `company_name` (NOT NULL), `form_type`, `filing_date`, `filing_url` |
| Parsed term sheet | `distribution_ratio`, `record_date`, `distribution_date`, `term_sheet_available`, `term_sheet_text` (raw information-statement prose kept for the LLM to read the spin thesis), `parent_symbol` (best-effort parent ticker for the size-ratio) |
| Lifecycle | `status` (TEXT, validated by the Java `SpinStatus` enum — **not** a DB enum, same convention as `executor_signal.status`), plus audit timestamps `discovered_at`, `last_checked_at`, `distributed_at`, `settled_at`, `abandoned_at` |
| Stage enrichment | `registered_snapshot`, `distributed_snapshot`, `settled_snapshot` (JSONB — the per-stage balance-sheet / size-forced-selling / valuation snapshots) |
| Promotion | `promoted_at`, `promoted_prey_id` (a **soft** link to the emitted prey — no hard FK) |

- **Status enum** (`SpinStatus`): `REGISTERED` → `WHEN_ISSUED` → `DISTRIBUTED` →
  `SETTLED` / `ABANDONED`. Forward-only; `SETTLED` and `ABANDONED` are terminal.
  Every status change is a guarded CAS (`WHERE status = <from>`), so a concurrent or
  duplicate reconcile is a no-op and a transition never reverses.
- **Idempotency:** expression unique index `uq_spin_candidate_natural` on
  `COALESCE(cik, lower(company_name))` — one row per spin-co, keyed on its CIK when
  known, degrading to the lowercased company name before a CIK is available. The
  ingestion upsert (`ON CONFLICT DO NOTHING`) targets this expression and the
  `SpinoffScreener` dedup key mirrors it exactly (same technique as V21's
  `uq_prey_natural_day`), so a re-run never duplicates a spin-co nor resets its
  lifecycle.
- **Supporting indexes:** `idx_spin_candidate_last_checked` on `last_checked_at`
  (the reconciler's oldest-checked-first work-queue scan); partial
  `idx_spin_candidate_promotable` on `(status, distributed_at) WHERE promoted_at IS
  NULL` (the DISTRIBUTED-not-yet-promoted promotion scan).
- Like the Executor tables, `spin_candidate` carries **no** `user_id` column — it
  backs the single research pipeline, not per-user data.

**Index-reconstitution lifecycle table (V27): `index_event`.** Backs strigoi-index's
announcement-anchored lifecycle (see `documentation/strigoi.md`, "Strigoi-Index:
announcement-anchored lifecycle"), turning the hunter from a stateless single-shot
constituents scan into a tracker that follows each announced S&P/Russell constituent
change from its public announcement (window still open) through the effective day
into the run-up/reversal observation window. One row per announced change;
`IndexEventRepository` is JdbcClient-based (explicit `INSERT … ON CONFLICT DO NOTHING`
+ guarded compare-and-set UPDATEs), mirroring `SpinCandidateRepository` / `PreyRepository`.

| Group | Columns |
|---|---|
| Change identity | `symbol` (NOT NULL), `company_name` (nullable — the change feed emits ticker-level changes only), `index_name` (NOT NULL — `sp500`/`russell1000`/`russell2000`), `action` (NOT NULL — `add`/`remove`), `source` (NOT NULL — `sp_press`/`russell_reconstitution`), `announcement_date` (DATE NOT NULL), `effective_date` (DATE NOT NULL) |
| Lifecycle | `status` (TEXT DEFAULT `'ANNOUNCED'`, validated by the Java `IndexEventStatus` enum — **not** a DB enum, same convention as `spin_candidate.status`), plus audit timestamps `discovered_at`, `last_checked_at`, `effective_at`, `closed_at`, `abandoned_at` |
| Stage enrichment | `announced_snapshot`, `post_snapshot` (JSONB — the demand/liquidity and run-up/reversal snapshots; **only two** because EFFECTIVE is a transient calendar tick with no observation stage of its own, so its drift read lands in `post_snapshot`) |
| Promotion | `promoted_at`, `promoted_prey_id` (a **soft** link to the emitted prey — TEXT, no hard FK) |

- **Status enum** (`IndexEventStatus`): `ANNOUNCED` → `EFFECTIVE` → `POST` →
  `CLOSED`, with `ANNOUNCED` → `ABANDONED` as a terminal safety-valve. Forward-only;
  `CLOSED` and `ABANDONED` are terminal. Every status change is a guarded CAS
  (`WHERE status = <from>`), so a concurrent or duplicate reconcile is a no-op and a
  transition never reverses. Transitions are **pure calendar** (zero Agora calls in
  the reconciler — the effective date is already authoritative on every row).
- **Idempotency:** expression unique index `uq_index_event_natural` on
  `(index_name, upper(symbol), action, effective_date)` — one row per announced
  change. Subjects are existing ticker-bearing companies, so there is no CIK/name
  degradation like spin's natural key. The ingestion upsert (`ON CONFLICT DO
  NOTHING`) targets this exact expression, so a re-run never duplicates a change nor
  resets its lifecycle. Rows missing an announcement or effective date are dropped at
  ingest (both back NOT NULL columns).
- **Supporting indexes:** `idx_index_event_last_checked` on `last_checked_at` (the
  reconciler's oldest-checked-first work-queue scan); partial
  `idx_index_event_promotable` on `(status, effective_date) WHERE promoted_at IS NULL`
  (the ANNOUNCED-not-yet-promoted promotion / active-window scans).
- Like the Executor and `spin_candidate` tables, `index_event` carries **no**
  `user_id` column — it backs the single research pipeline, not per-user data.

## Data Flow

```
External sources (EDGAR, prices, news, calendar)
          │
  hunting-grounds adapters
          │
   ┌──────┴──────────────────────┐
   │ Strigoi (6, nightly)         │→ Prey → dracul.prey ─┐
   │ Voievod (1, daily)           │→ Verdicts → dracul.verdicts
   │ Daywalker (1, streaming)     │→ Alerts → dracul.daywalker_alerts
   │ Renfield (1, daily @ 12 UTC) │→ Proposals → dracul.trade_proposals
   └─────────────────────────────┘                      │
                │                    (executor enabled)  │
                │        PreySignalEmitter ◀─────────────┘
                │                    │
                │        dracul.executor_signal (PENDING)
                │
    Synthesizer (creates Verdicts from agreeing Prey)
                │
    dracul.verdicts
                │
    dracul-app REST API + SSE
                │
    Chronicle (Vue 3 frontend)
                │
    User — reads, approves patterns, manages watchlist
```

**Daywalker sweep universe (A2):** The Daywalker monitors the **union**
of two disjoint position sources: the watchlist (operator-curated) and
the live depot (`HeldPositionService.openPositions("depot-1")`,
broker-sourced). Per-symbol triggers are allocated to the **depot
representative** (if a symbol appears in both, only the depot's position
contributes context); a symbol with a depot position but no watchlist
entry generates alerts keyed by symbol alone (no `watchlist_item_id`),
routed to `dracul.primary-user-email`. Engine cooldown is per
`(symbol, trigger_type)`, **owner-agnostic**: a NEGATIVE_NEWS event on
SAP.DE cools any further NEGATIVE_NEWS events on SAP.DE for the
configured duration (default 3600 s), regardless of whether the alert
was depot- or watchlist-sourced or which owner holds it.

**Portfolio-aware news implication (T2.2):** Daywalker's renfield input and macroeconomic-news triggers now carry extended portfolio context. The renfield analysis (`fetch_held_positions` tool) reports a `position` block per held symbol: `{direction, entry, gain_loss_pct, weight_pct, active_stop, sector}` (FX-converted to display currency). Short positions respect sign-correct math: breach detection is direction-aware (e.g. a short entered at 100 with stop at 110 breaches its stop when price *rises* to or above 110, reversed from long logic). The macro trigger (`MACRO_PORTFOLIO`) collects macro-only headline assessments across a session poll, deduplicates and caps them at 10 per poll, and issues one consolidated alert keyed by the pseudo-symbol `PORTFOLIO` (visible in Telegram/SSE only; excluded from Chronicle watchlist UI and daywalker-deep escalation). Alerts persist under `PORTFOLIO` with `portfolio_snapshot` populated — FX-converted weights mapped to the display currency under strict hasRate discipline. `MACRO_PORTFOLIO` assessments are routed to the primary owner (`dracul.primary-user-email`). Cooldown for `MACRO_PORTFOLIO` is dual-scoped: `(PORTFOLIO, MACRO_PORTFOLIO)` cooldown (default 28800 s = 8 h) ensures at most 2 per 16-hour session; per-symbol NEGATIVE_NEWS cooldown (default 3600 s) remains unchanged. Sector metadata is cached (positive TTL 86400 s = 24 h, negative TTL 3600 s = 1 h) to avoid repeated lookups for inactive symbols.

**Prey → Verdict synthesis (Voievod):** Each weekday morning a reasoning-tier
`ScheduledBee` groups all open prey (within their declared horizon) by symbol.
Any symbol confirmed by ≥2 distinct Strigoi becomes a consensus cluster. The
consensus score is deterministic noisy-OR (`1 − ∏(1 − confidenceᵢ)`) and is
recomputed from the database at completion time — the LLM's tool-call snapshot
is advisory only. The LLM writes the narrative `summary` and may legitimately
drop a cluster it judges coincidental. Verdicts are upserted per symbol; a
verdict that already carries a user decision (`decision` IS NOT NULL) is never
overwritten. The new column `contributing_prey_ids` (JSONB, V6) captures the
exact prey UUIDs used for each upsert, enabling future outcome analysis.

**Live alerts (SSE):** after persisting an alert, `DaywalkerCompletionService`
publishes one `DaywalkerAlertCreatedEvent` **per eligible owner** (carrying that
owner's email) → an `@EventListener` bridge calls
`SseBroadcaster.sendToOwner(owner, "alert.new", …)` → `GET /api/events` delivers it
only to the streams that owner has open (Chronicle live panel). Each emitter is
tagged at connect time with the connecting user's email (`CurrentUserHolder`, set
by `CloudflareAccessFilter`), so the transient toast respects the same per-owner
boundary as the persisted rows. The broadcaster retains a generic `broadcast`
(all streams) seam for future global event types.

## Authentication & multi-user

**Identity**: Cloudflare Access sits in front of all user-facing endpoints.
Dracul verifies the `Cf-Access-Jwt-Assertion` JWT (JWKS signature + audience
check) and derives the authenticated user from the token's email claim. Webhook
endpoints retain machine bearer-token auth and bypass Cloudflare verification.

**Watchlist visibility**: the watchlist is per-owner with
collaborative read-all / write-own semantics. `GET /api/watchlist` returns all
users' items (each row carries an `owner` field); `POST` creates items owned by
the calling user; `PATCH` / `DELETE` / `PATCH …/position` enforce ownership —
attempting to modify another user's item returns 403.

**Other domain data**: prey, verdicts, patterns, and alerts remain shared under
`user_id = 'default'` for now; per-user scoping for these objects is a Phase-2
concern.

**Legacy data migration**: on startup, any watchlist rows with `user_id =
'default'` are reassigned to `DRACUL_PRIMARY_USER_EMAIL`, preventing the
existing dataset from becoming ownerless after the auth migration.
