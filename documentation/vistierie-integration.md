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
| `routine` (pre-filter) | Daywalker Haiku pre-filter | daywalker (first stage) |
| `reasoning` (escalation) | Daywalker Sonnet full assessment | daywalker (second stage) |

## Bee lifecycle types

Dracul uses two of Vistierie's Bee lifecycle models:

| Bee | Type | Trigger |
|---|---|---|
| Strigoi (6 agents) | ScheduledBee | Cron nightly |
| Voievod | ScheduledBee | Cron weekly (Sunday) |
| Daywalker | StreamingBee | Starts at market open, runs continuously |

The `StreamingBee` pattern is a Vistierie extension introduced to support
Dracul's Daywalker. If Vistierie does not yet expose this interface, it
must be added upstream before the Daywalker can be implemented — never
patched into Dracul.

## Tool webhooks

Each Strigoi declares its tools (`prey.scan`, `filing.fetch`, etc.) as
webhook URLs pointing back into `dracul-app`. Vistierie calls those URLs
from inside the tool-dispatch loop. The shared secret is
`DRACUL_TOOL_WEBHOOK_TOKEN`.

## Completion webhook

When a ScheduledBee run finishes, Vistierie POSTs the validated agent
output to a single Dracul completion webhook. Dracul writes the result
into the appropriate table (`dracul.prey`, `dracul.patterns`).

## Cost and run history

Dracul does **not** maintain its own cost ledger. It proxies
`/api/cost` and `/api/cost/runs` from Vistierie's Run History API.
The Vistierie view in Chronicle displays this data directly.

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
