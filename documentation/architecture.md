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
          ┌────────────────────┼────────────────────┐
          │                    │                    │
   ┌──────▼──────┐      ┌─────▼──────┐      ┌─────▼──────┐
   │   STRIGOI   │      │  VOIEVOD   │      │ DAYWALKER  │
   │  6 hunters  │      │ 1 reviewer │      │ 1 guardian │
   │  scheduled  │      │  scheduled │      │ streaming  │
   │  nightly    │      │  daily     │      │ mkt hours  │
   └─────────────┘      └─────────────┘     └─────────────┘
    hunts prey           reviews outcomes    guards watchlist
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
│   └── watchlist-monitor, trigger-detectors, alert-pipeline
│
├── dracul-synthesizer/         # Verdict generation
│   └── consensus-detector, narrative-generator
│
├── dracul-hunting-grounds/     # Market-data adapters
│   ├── edgar-adapter/          # SEC EDGAR (Form 10, 4, 8-K)
│   ├── prices-adapter/         # Yahoo / Alpha Vantage / Polygon
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
| `prey` | Immutable Strigoi findings; includes outcome columns filled by Voievod |
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
`WatchlistPriceRepository.distinctTickers()`, fetches live quotes via the
Finnhub → Twelve Data → Yahoo chain, and writes `current_price` /
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

**Exit signals table (V11):**
- `exit_signals` — one row per gropar verdict per position per run: `id` (UUID PK), `symbol` (TEXT NOT NULL), `verdict` (TEXT NOT NULL, CHECK: SELL / TRIM / HOLD), `rationale` (TEXT), `confidence` (NUMERIC(4,3)), `vistierie_run_id` (TEXT), `created_at` (TIMESTAMPTZ NOT NULL DEFAULT now()), `user_id` (TEXT NOT NULL DEFAULT 'default')
- index on `(user_id, symbol, created_at DESC)`
- Gropar data flow: HELD watchlist positions → daily OHLC history (TwelveData `/time_series outputsize=N`, Yahoo `range=1y&interval=1d`) → `ExitIndicatorService` (ATR/Chandelier stop, MA cross, 52-week proximity, gain/loss thresholds, time stop) → reasoning-tier LLM judgment → `ExitSignal` (SELL / TRIM / HOLD) → `dracul.exit_signals` → `GET /api/exit-signals` + Telegram push for SELL/TRIM verdicts.
- Gropar position guard: `WatchlistController` publishes a `WatchlistChangedEvent` after every watchlist mutation. `GroparPauseReconciler` (present only when `dracul.gropar.enabled=true`, `@Order(30)` so it runs after `GenericAgentRegistrar`) listens to that event and to `ApplicationReadyEvent`, counts held positions (`WatchlistRepository.countHeldByUser`), and calls `VistierieClient.patchAgent("gropar", heldCount == 0)`. An in-memory last-applied state suppresses redundant Vistierie calls; a failed patch is logged and retried on the next event. gropar's pause is thus system-managed (operator uses the `enabled` flag, not the manual pause toggle).

**Verdict native currency (V13):**
- `verdicts.currency` (TEXT, nullable) — ISO 4217 code of the native currency in which `current_price` was recorded (e.g. `"USD"`). Added by `V13__verdict_currency.sql`. Conversion to the operator's display currency happens at read time in `VerdictController` via `VerdictCurrencyMapper`, mirroring the watchlist path's `WatchlistCurrencyMapper`. When `currency` equals the display currency no conversion is applied and the native fields in the API response are null.

**Watchlist position columns (V14):**
- `entry_date` (DATE, nullable, default `CURRENT_DATE`) — real purchase date, backfilled to `added_at` for existing rows; surfaced as `entryDate` on the read/write API.
- `initial_stop` (NUMERIC(12,4), nullable) — frozen native-currency ATR stop computed at entry (`entry_price − initial_stop_atr_multiple × ATR22`); used by the Groparul R-framework to derive the risk unit R and the giveback guard.

**Watchlist risk-snapshot columns (V15):**

Four nullable columns added to `watchlist_items` to persist the per-position risk snapshot written by gropar's `fetch_held_positions` tool call (overwritten on every gropar run) and read by the morning report:

- `active_stop` (NUMERIC(12,4), nullable) — active trailing stop = `max(initial_stop, chandelier)`
- `next_target_2r` (NUMERIC(12,4), nullable) — 2R price target (`entryPrice + 2 × initialRisk`)
- `current_close` (NUMERIC(12,4), nullable) — last close price at the time of the gropar run
- `risk_snapshot_at` (TIMESTAMPTZ, nullable) — timestamp of the most recent gropar snapshot write

These columns are `null` until gropar has run at least once after V15 is applied. `GET /api/morning-report` reads them to build the report projection without issuing any market-data calls.

**Watchlist ATR column (V16):**

- `atr` (NUMERIC(12,4), nullable) — Average True Range (ATR22) of the position at the time of the most recent gropar run. Written by gropar's `fetch_held_positions` tool call alongside the V15 snapshot columns. Used by `StopProximityWatcher` to derive the stop-proximity zone width (`active_stop + atr-multiple × ATR`). `null` until gropar has run at least once after V16 is applied.

**Stop-proximity watcher (`StopProximityWatcher`):**

A deterministic, intraday `@Scheduled` cron (no LLM, no Vistierie agent) in package `de.visterion.dracul.stopguard`. Disabled by default (`dracul.stopguard.enabled=false`). When enabled it fires every ~15 minutes during the US session (`0 */15 9-16 * * 1-5`, zone America/New_York), loads all held positions with their persisted V15/V16 snapshot, batch-fetches live prices via the `@Primary` MarketDataPort (TwelveData → Yahoo, using the existing adapter cache), and classifies each position via `StopZoneEvaluator`:

| Condition | Zone | Severity |
|---|---|---|
| `price ≤ active_stop` | `STOP_BREACHED` | CRITICAL |
| `active_stop < price ≤ active_stop + atr-multiple × ATR` | `STOP_PROXIMITY` | WARNING |
| above proximity band, or stop/ATR null | no alert | — |

`StopAlertEmitter` persists qualifying alerts to the existing `daywalker_alerts` store, broadcasts `alert.new` over SSE to the live panel, and sends a German-language Telegram push. Re-alert cooldown is per `(owner, symbol, zone)` (default ≈ 23 h); `STOP_PROXIMITY` and `STOP_BREACHED` have independent cooldowns so a price breach triggers an immediate escalation alert even if a proximity alert was already sent today.

**Agent definition tables (V10):**

Two new tables under the `dracul` schema hold runtime-editable agent definitions:

- `agent_definition` — one row per agent: `name` (TEXT PK), `model_purpose` (TEXT NOT NULL), `prompt_text` (TEXT NOT NULL), `output_schema` (JSONB NOT NULL), `schedule` (TEXT), `max_turns` (INT NOT NULL), `max_run_seconds` (INT NOT NULL), `completion_path` (TEXT NOT NULL), `event_source_path` (TEXT), `session_duration_seconds` (INT), `poll_interval_seconds` (INT), `enabled` (BOOLEAN NOT NULL DEFAULT TRUE), `updated_at` (TIMESTAMPTZ NOT NULL DEFAULT now())
- `agent_tool_binding` — one row per tool binding: `agent_name` (TEXT NOT NULL, FK → `agent_definition(name)` ON DELETE CASCADE), `tool_name` (TEXT NOT NULL), `description` (TEXT), `default_params` (JSONB), `ordinal` (INT NOT NULL DEFAULT 0), PK `(agent_name, tool_name)`; index `idx_agent_tool_binding_agent` on `agent_name`

**Agent bootstrap and registration flow:**

1. `AgentDefinitionBootstrap` (runs at startup, `@EventListener(ApplicationReadyEvent)`) iterates all `AgentDefaultProvider` beans and upserts each default into `agent_definition` + `agent_tool_binding` using insert-if-absent semantics. Rows that already exist (from a previous deploy or runtime edit via the REST API) are not overwritten, so manual customisations survive redeploys.
2. `GenericAgentRegistrar` reads all agent definitions from the DB, builds a `CreateAgentRequest` for each, prepends `dracul.public-url` to all webhook callback paths, appends the current language directive to the system prompt, and registers or updates the agent with Vistierie. It re-runs whenever an `AgentDefinitionChangedEvent` or `LanguageChangedEvent` is published (e.g. after a `PUT /api/settings/agents/{name}/definition` or `PUT /api/settings/language`), making definition changes effective immediately without a restart.

All tables include a `user_id TEXT NOT NULL DEFAULT 'default'` column for
Phase-2 multi-user readiness. Schema changes require a Flyway migration and
an update to this document.

## Data Flow

```
External sources (EDGAR, prices, news, calendar)
          │
  hunting-grounds adapters
          │
   ┌──────┴──────────────────────┐
   │ Strigoi (6, nightly)         │→ Prey → dracul.prey
   │ Voievod (1, daily)           │→ Verdicts → dracul.verdicts
   │ Daywalker (1, streaming)     │→ Alerts → dracul.daywalker_alerts
   └─────────────────────────────┘
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
