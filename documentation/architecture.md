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
   │  nightly    │      │  weekly    │      │ mkt hours  │
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
| `watchlist_items` | Items the Daywalker monitors; `active` generated column |
| `daywalker_alerts` | Every Daywalker trigger, with LLM assessment and notification flag |

**Verdict columns added:**
- `decision` (TEXT, nullable) — CHECK constraint: TRACK, INTERESTING, DISMISS, ACTED
- `decided_at` (TIMESTAMPTZ, nullable) — timestamp of decision

**Watchlist indexes:**
- `uq_watchlist_user_ticker` (UNIQUE) — composite index on (user_id, ticker) for idempotent POST

**Daywalker-alert columns (V4):**
- `symbol`, `trigger_type`, `thesis`, `severity`, `vistierie_run_id` (TEXT, nullable)
- `confidence` (NUMERIC(4,3), nullable)
- `created_at` (TIMESTAMPTZ, default now()) — drives the per-`(symbol, trigger_type)` cooldown
- index `idx_daywalker_alerts_symbol_trigger` on (user_id, symbol, trigger_type, created_at DESC)

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
   │ Voievod (1, weekly)          │→ Patterns → dracul.patterns
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

## Multi-User Readiness

Phase 1 is single-user. `user_id` columns exist everywhere but are always
`"default"`. Phase 2 adds auth/RBAC and row-level security without schema
changes.
