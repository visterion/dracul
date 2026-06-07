# Vistierie integration

Dracul is a Vistierie consumer. This page documents the boundary between
the two systems. The split is non-negotiable — see `CLAUDE.md` for the
authoritative ownership table.

## Tenant

Dracul registers itself as the `dracul` tenant inside Vistierie. Routing
rules map `<dracul, *, reasoning>` and `<dracul, *, routine>` to concrete
provider+model combinations. Switching a Strigoi from Sonnet to Haiku is a
routing-rule edit in Vistierie, not a code change in Dracul.

## Authentication

Vistierie requires a `Bearer` token on every path except `/healthz`, `/readyz`,
`/actuator/*`. There are two token classes:

- **Tenant token** — authorises tenant endpoints (`/agents`, `/runs`). Issued once by
  `POST /admin/tenants {"name":"dracul"}`. Dracul holds it as `VISTIERIE_TENANT_TOKEN`.
- **Admin token** — authorises `/admin/*` (cost, routing rules, budget, kill). Dracul holds
  it as `VISTIERIE_ADMIN_TOKEN`. Note: this token has authority over all tenants; Dracul
  holding it is an accepted v1 trust-boundary decision (same boundary as HiveMem).

Dracul's `HttpVistierieClient` sends the tenant token on tenant calls and the admin token on
`/admin/*` calls via two separate `RestClient`s.

## Tier conventions

| Tier | Use for | Agents |
|---|---|---|
| `reasoning` | Dense filings, multi-factor judgement | strigoi-spin, strigoi-lazarus, strigoi-merger, Voievod |
| `routine` | Pattern-matching, simple classification | strigoi-insider, strigoi-echo, strigoi-index |
| `reasoning` | Daywalker per-event assessment (v1: single Sonnet) | daywalker |

## Bee lifecycle types

Dracul uses two of Vistierie's Bee lifecycle models:

| Bee | Type | Trigger |
|---|---|---|
| Strigoi (6 agents) | ScheduledBee | Cron nightly |
| Voievod (synthesizer, Etappe 7) | ScheduledBee | Cron daily on weekdays (~08:00 UTC); reasoning tier. Note: a separate outcome-analysis learning loop (Etappe 8) is a later addition and will run on a different schedule. |
| Daywalker | StreamingBee | Window-bounded session at market open; polls an event-source webhook every 5 min |

The `StreamingBee` pattern is a Vistierie extension introduced to support
Dracul's Daywalker. If Vistierie does not yet expose this interface, it
must be added upstream before the Daywalker can be implemented — never
patched into Dracul.

## Tool webhooks

Each Strigoi declares its tools (`prey.scan`, `filing.fetch`, etc.) as
webhook URLs pointing back into `dracul-app`. Vistierie calls those URLs
from inside the tool-dispatch loop. The shared secret is per-Strigoi; the
Strigoi-Insider uses `STRIGOI_INSIDER_TOKEN` (set both as Dracul's inbound
verifier and registered with Vistierie as the tool + completion webhook token).

## Completion webhook

When a ScheduledBee run finishes, Vistierie POSTs the validated agent
output to a single Dracul completion webhook. Dracul writes the result
into the appropriate table (`dracul.prey`, `dracul.patterns`).

## Cost and run history

Dracul does **not** maintain its own cost ledger. It proxies
`/api/cost` and `/api/cost/runs` from Vistierie's Run History API.
The Vistierie view in Chronicle displays this data directly.

The `/api/vistierie` cost panel is assembled by `VistierieDataService`,
which needs data from several Vistierie endpoints — routing rules, the
agent list, one detail call **per** strigoi, and the cost dashboard
(~15 blocking calls in total). These are fanned out across virtual
threads rather than fetched serially, and the assembled result is
cached for `VISTIERIE_CACHE_TTL_SECONDS` (default 30s). This keeps the
Chronicle view load fast; previously the serial fetch dominated it at
~2s.

## Agent system_prompt localisation

Every agent (all 6 Strigoi, Voievod, and Daywalker) has its `system_prompt`
localised at registration time. `LanguageDirective.append` appends an
instruction in the configured language to the end of the prompt loaded from
the classpath (`prompts/<agent>.md`). The language is read from the
`app_settings` table via `AppSettingsRepository.getLanguage()`.

When the language setting is changed via `PUT /api/settings/language`, Dracul
publishes a `LanguageChangedEvent`. Every registrar listens for this event via
`@EventListener(LanguageChangedEvent.class)` and immediately re-registers the
agent with the updated (re-localised) prompt.

## What Vistierie owns vs what Dracul owns

| Vistierie owns | Dracul owns |
|---|---|
| Bee/Strigoi runtime, schedule, recursion, context shielding | Strigoi domain logic and prompts |
| Provider plugins, tier-based routing, cost ledger, kill switch | Market-data adapters (EDGAR, prices, news, calendar) |
| Run history, audit, batch API | Pre-screen logic (deterministic filters before LLM) |
| Webhook completion delivery | `Prey` / `Verdict` / `Pattern` / `Alert` domain, persistence, frontend, backtest |
| StreamingBee lifecycle | Daywalker trigger logic and alert assessment |

Spotting investment terms (Prey, Verdict, Strigoi, Pattern Library) inside
Vistierie's codebase is a layer-violation bug and must be moved to Dracul.
