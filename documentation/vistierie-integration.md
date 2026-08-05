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
| `reasoning` | Dense filings, multi-factor judgement | strigoi-spin, strigoi-lazarus, strigoi-merger, strigoi-insider, strigoi-echo, Voievod |
| `routine` | Pattern-matching, simple classification | strigoi-index |
| `reasoning` | Daywalker per-event assessment (v1: single Sonnet) | daywalker |

## Bee lifecycle types

Dracul uses two of Vistierie's Bee lifecycle models:

| Bee | Type | Trigger |
|---|---|---|
| Strigoi (6 agents) | ScheduledBee | Cron nightly |
| Voievod (synthesizer, Etappe 7) | ScheduledBee | Cron daily on weekdays (~08:00 UTC); reasoning tier. Note: a separate outcome-analysis learning loop (Etappe 8) is a later addition and will run on a different schedule. |
| Daywalker | StreamingBee | Window-bounded session at market open; polls an event-source webhook every 5 min |
| Daywalker-Deep | ScheduledBee (never scheduled — `schedule=null`) | Trigger-only: `DaywalkerCompletionService` calls `VistierieClient.triggerRun("daywalker-deep", input)` for a low-confidence CRITICAL assessment; reasoning tier |
| Executor (slice 1) | ScheduledBee | Cron (`dracul.executor.schedule`, blank by default = manual-only via `POST /api/executor/run`); reasoning tier |

The Executor's `ToolBinding` list (`ExecutorDefaults.executorAgentDefaults`,
registered in this order) is: `fetch_pending_signals`, `get_account`,
`list_positions`, `place_entry`, `submit_decision`, `fetch_open_positions`,
`exit_position`, `add_tranche` — the last one, `add_tranche`, was added by
the entry-completeness work to let the LLM request a code-verified second
tranche on an already-open position (see `documentation/api.md`'s
`POST /api/executor/tools/add-tranche`). Adding a tool binding changes the
agent's registered definition just like a prompt/schema edit does, so it is
subject to the same insert-if-absent bootstrap behavior below: on an
already-registered `executor` agent, a deploy that adds `add_tranche` does
**not** propagate to Vistierie on its own — the same
`POST /api/settings/agents/executor/definition/reset` step is required.

### Which fields a redeploy propagates, and which need the reset

The asymmetry is easy to get backwards, so state it explicitly. Dracul builds a
tool catalog from code on every boot and registers it, and Vistierie's agent
row is seeded with `insertIfAbsent`. The consequence:

| Field | Source of truth | Reaches Vistierie on a plain redeploy? |
|---|---|---|
| tool `name`, `description`, `input_schema` | Dracul's code catalog | **yes** |
| tool `webhook_url`, `webhook_timeout_seconds` | Dracul's code catalog | **yes** (but see below) |
| adding or removing a tool *binding* on an agent | agent definition row | **no** — needs the reset |
| `prompt_text` | agent definition row | **no** — needs the reset |
| `output_schema` | agent definition row | **no** — needs the reset |
| `max_run_seconds`, `schedule`, `max_turns` | agent definition row | **no** — needs the reset |

> **`webhook_timeout_seconds` propagates but does nothing** (verified
> 2026-08-04). Vistierie declares it on the tool definition and never applies
> it, and the RestClient that calls the webhook uses
> `SimpleClientHttpRequestFactory` with an **infinite** read timeout. Nothing
> upstream cuts a long tool call short — treat the number as documentation of
> the budget a tool needs, and never diagnose a stalled run as "the tool timed
> out". Any ceiling a tool needs has to be enforced inside the handler.

So editing a tool's input schema in Dracul's code does propagate; editing a
prompt or an output schema does not, and a definition that already exists is
never overwritten. Anything in the second group needs
`POST /api/settings/agents/<name>/definition/reset` after the deploy — verify
the *registered* definition, not the repo file.

**An input schema is a description, not a contract.** Nothing validates a call's
arguments against it: Vistierie checks `input_schema` only as a schema, at
definition time, and its single `schemas.validate` call site validates an
agent's *output*. The bridge appends the schema to the tool description, so its
real effect is on the model — which is why registering `submit_decision` with
the argument-less `{"type":"object","properties":{}}` schema made the agent call
it with `{}` and record nothing, and why the *handler* must still cope with what
actually arrives. The bridge stringifies tool arguments often enough that a
declared array reaches the server as a JSON string; a handler that trusts the
declared type answers "recorded 0" and loses the call.

The `StreamingBee` pattern is a Vistierie extension introduced to support
Dracul's Daywalker. If Vistierie does not yet expose this interface, it
must be added upstream before the Daywalker can be implemented — never
patched into Dracul.

## Programmatic run trigger with an input payload

`VistierieClient.triggerRun(String agentName)` (no input) is a default method
delegating to `triggerRun(String agentName, Map<String, Object> input)`, which POSTs
`/agents/{name}/run` with body `{"payload": <input>}` — Vistierie's `CreateRunRequest`
contract (`payload`, `completion_webhook`, `completion_webhook_token`; the latter two
are omitted so the agent's registered completion webhook is used). `payload` becomes
the triggered run's context available to the agent turn, which is how
`DaywalkerCompletionService`'s escalation trigger forwards `symbol`, `trigger_type`,
and `thesis` to `daywalker-deep` without it needing a tool call to fetch that context
itself — see "Daywalker reasoning-tier escalation" in `documentation/strigoi.md`.
`HttpVistierieClient`/`MockVistierieClient` both implement the 2-arg overload; the
1-arg overload (used by `ExecutorRunController`/`StrigoiRunController` for a plain
manual trigger) needs no code change.

## Tool webhooks

Each Strigoi declares its tools (`prey.scan`, `filing.fetch`, etc.) as
webhook URLs pointing back into `dracul-app`. Vistierie calls those URLs
from inside the tool-dispatch loop. The shared secret is per-Strigoi; the
Strigoi-Insider uses `STRIGOI_INSIDER_TOKEN` (set both as Dracul's inbound
verifier and registered with Vistierie as the tool + completion webhook token).

**A 4xx from a tool webhook is terminal; a 5xx is retried once and then terminal too.**
Vistierie's `ToolDispatcher` treats any 4xx client-error status as an immediate,
non-retryable failure of the tool call. A 5xx is wrapped as a transient error and retried
exactly once; if the retry also fails, the outcome is the same as a 4xx. Either way,
`AgentRunner` marks the whole run `failed` — the completion webhook above never fires, and
every prey the agent had already produced in that run is lost, not just the one tool call
that failed.

The practical consequence for anything Dracul exposes as a tool webhook: **a business-level
failure (an unhealthy data source, a candidate that can't be enriched, a malformed but
otherwise-recoverable input) must never be represented as a non-2xx response.** It has to be
encoded inside a `200` body instead — Dracul's six hunter tool endpoints do this via a
`data_source_health.status = "unavailable"` envelope (see `documentation/api.md`, "Hunter
tool endpoint response contract"). Only `401` (bad/missing bearer token — a configuration
error a run should not survive) is treated as an acceptable non-2xx on those six endpoints;
the same discipline has not yet been applied to gropar, voievod, or the executor's webhook
endpoints, which can still fail a run the same way.

## Completion webhook

When a ScheduledBee run finishes, Vistierie POSTs the validated agent
output to a single Dracul completion webhook. Dracul writes the result
into the appropriate table (`dracul.prey`, `dracul.patterns`).

Every completion webhook carries an `X-Vistierie-Run-Id` header. As of
V39/Schicht 1, the Strigoi hunt `/complete` webhook (`HuntController.complete`)
persists it into `prey.run_id` (TEXT, nullable) via
`PreyRepository.insertAll(prey, runId)`, instead of only logging it. This
forward-only column (pre-V39 prey stays `run_id = null`) is the anchor a later
retrieval step (Schicht 2) will use to fetch the full raw Vistierie run
transcript for a given prey finding. Other completion webhooks
(gropar/renfield/daywalker/voievod/executor) still only log the header — they
do not yet persist it.

## Agent budgets and definition updates

Two operational gotchas apply to any scheduled agent registered with
Vistierie, including the Executor:

- **A scheduled agent needs a Vistierie budget.** Without one, Vistierie's
  `AgentService.patch` throws `BudgetException: agent budget missing` (HTTP
  500) on **any** pause/unpause toggle, leaving the agent stuck at its
  current pause state. Set one via the admin endpoint, mirroring voievod
  ($1/day, $10/month):
  ```
  curl -s -X PATCH -H "Authorization: Bearer $VISTIERIE_ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"daily_cap_micros":1000000,"monthly_cap_micros":10000000}' \
    http://localhost:8090/admin/tenants/dracul/agents/executor/budget
  ```
- **Prompt/schema/tool changes to an already-registered agent need a
  definition reset.** `AgentDefinitionBootstrap` is insert-if-absent, so
  once `executor`'s row exists in `agent_definition` a deploy that changes
  `prompts/executor.md` or `schemas/executor-decision.json` does **not**
  propagate automatically — `GenericAgentRegistrar.matches()` compares the
  DB-stored (old) definition against Vistierie's (old) definition and finds
  them equal, so it skips the update. Trigger
  `POST /api/settings/agents/executor/definition/reset` to re-apply the code
  default (fires `AgentDefinitionChangedEvent` → re-registration). Verify
  with `GET /agents/executor` on Vistierie — `output_schema` /
  `system_prompt` should reflect the change and `version` should bump.

`GenericAgentRegistrar.matches()` also compares StreamingBee-only fields
(`event_source_url`, `session_duration_seconds`, `poll_interval_seconds`)
for the Daywalker, so a change to any of those three now triggers a
re-register on the next `registerAll()`/`onChanged()` pass, the same as a
prompt or schema change. This closes a former gap where `AgentDetail`
carried no streaming fields and drift there went undetected until a manual
re-register.

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

## Run observability reads

`VistierieClient` exposes three read methods that wrap Vistierie's
SP-V run-observability endpoints. All calls are scoped to the `dracul`
tenant and degrade gracefully on error (empty list or `null`).

| Method | Vistierie endpoint | Returns on error |
|---|---|---|
| `searchRuns(query, …)` | `GET /runs/search` | empty list |
| `listAgentRuns(agent, limit, offset)` | `GET /runs` (tenant-scoped) | empty list |
| `getRunTranscript(runId, view)` | `GET /runs/{id}/transcript?view=digest\|compact\|full` | `null` |
| `getRunToolCall(runId, toolUseId)` | `GET /runs/{id}/tool-calls/{toolUseId}` | `null` |

**`listAgentRuns(agent, limit, offset)`** — lists the tenant's own agent runs
(newest first) for the operator-gated activity inspector (`GET
/api/inspector/runs`). Uses the **tenant-scoped** `/runs` endpoint (dracul only —
never `/admin/runs`, which is cross-tenant); the `agent` filter is applied
client-side because `/runs` ignores it. Paginates via `limit`/`offset`.

### Method details

**`searchRuns(query, ...)`** — ranked full-text search across run
transcripts. Returns a list of snippet hits (run ID, score, excerpt).
Useful for surfacing past runs that mention a specific ticker or filing.

**`getRunTranscript(runId, view)`** — retrieves a single run's transcript
at one of three verbosity levels. Since Schicht 2, `DepotController`'s
`GET /api/depots/run/{runId}/transcript` proxies this method with
`view="full"`, passing Vistierie's raw transcript body straight through
to Chronicle without transformation (exact prompt + raw LLM answer +
tool results, un-truncated). See `documentation/api.md` for the response
shape.

| `view` value | Content |
|---|---|
| `digest` | One-paragraph summary of the run |
| `compact` | Key messages and tool calls, condensed |
| `full` | Complete message history |

**`getRunToolCall(runId, toolUseId)`** — retrieves the raw input/output
of a single tool-call event within a run. Used to inspect what a Strigoi
passed to and received from a tool webhook.

### Error handling

All three methods catch any `RestClient` exception, log a warning, and
return the degraded value listed in the table above. Chronicle views that
call these methods must handle empty / `null` gracefully; no exception
propagates to the caller.

## What Vistierie owns vs what Dracul owns

| Vistierie owns | Dracul owns |
|---|---|
| Bee/Strigoi runtime, schedule, recursion, context shielding | Strigoi domain logic and prompts |
| Provider plugins, tier-based routing, cost ledger, kill switch | Hunting fetch + prices/OHLC consumed from Agora over MCP via six facades (`AgoraMarketData`, `AgoraFilings`, `AgoraCompanyData`, `AgoraEarnings`, `AgoraReference`, `AgoraIntraday`) |
| Run history, audit, batch API | Pre-screen logic (deterministic filters before LLM) |
| Webhook completion delivery | `Prey` / `Verdict` / `Pattern` / `Alert` domain, persistence, frontend, backtest |
| StreamingBee lifecycle | Daywalker trigger logic and alert assessment |

Spotting investment terms (Prey, Verdict, Strigoi, Pattern Library) inside
Vistierie's codebase is a layer-violation bug and must be moved to Dracul.

**Prices / OHLC via Agora:** Dracul no longer runs its own Yahoo / Twelve Data /
Finnhub price adapters. Quotes and daily OHLC come from the co-located **Agora**
service, consumed over Agora's MCP front-door (`get_quote` / `get_ohlc`) via the
generic `AgoraClient` and the `AgoraMarketData` facade. Agora owns provider
fallback and rate-limit handling; Dracul just maps the tool output to its
`MarketData` / `Quote` / `OhlcBar` DTOs.

**Hunting fetch via Agora (slice 7c):** the same principle extends to all
hunting-ground fetch — filings, news, recommendations, fundamentals,
earnings, index constituents, and intraday candles are consumed from Agora
over MCP via five domain facades in `de.visterion.dracul.hunting.agora`
(`AgoraFilings`, `AgoraCompanyData`, `AgoraEarnings`, `AgoraReference`,
`AgoraIntraday`). The direct EDGAR / Finnhub / Yahoo / Wikipedia hunting
adapters have been removed entirely; Agora owns provider selection, fallback,
and rate-limit handling for hunting fetch just as it does for prices/OHLC.

Agora must be deployed — with `get_company_profile` and `get_earnings_window`
available — before Dracul-7c.

**Term-sheet enrichment (2026-07-08):** strigoi-merger and strigoi-spin also
depend on Agora's `get_filing_text` tool (fetches a filing's primary document
as cleaned summary-term-sheet text), consumed via `AgoraFilings.filingText(url)`.
Fail-soft: an unavailable filing degrades to `FilingText.unavailable()` and the
strigoi LLM judges conservatively — the agents' `output_schema` is unchanged.

## Executor trading gateway: the `flatten` contract (2026-08-05)

The executor's write path to the broker goes through Agora's trading tools
(`place_bracket`, `flatten`, `modify_bracket`, `cancel_order`), called by
`AgoraExecutionGateway` with a non-live trading token (`dracul.executor.agora-trading-token`).
This is a separate integration from the MCP-based hunting/price facades above:
it is plain webhook calls (`POST {agora-base}/tools/{name}`), not MCP tool
calls inside an agent run, because the executor itself is the caller, not an
LLM tool-dispatch loop.

**`flatten(connection, symbol, fraction)`** closes all or part of a position.
Since 2026-08-05, a **partial** close (`fraction < 1.0`) additionally restores
the protective stop legs Agora cancels along the way, sized down to the
remaining holding, *before* placing the closing market order — a failed
closing order then costs nothing, instead of leaving the remainder
unprotected until the next maintenance run (BUG-S7). The response carries two
new fields, on **both** the accepted and the rejected branch:

| Field | Wire name(s) | Meaning |
|---|---|---|
| Restored protective legs | `protective_legs` (array) | Each entry has `replaces` (the old leg id it stands in for), `order_id` (the new id), `qty`, `price`. Parsed by `AgoraExecutionGateway.restoredLegs`, camelCase-tolerant (`orderId`/`order_id` etc. throughout the gateway). |
| Leg collapse flag | `legs_collapsed` (bool, default `false`) | `true` when the remaining holding was too small to give a two-tranche position two separate stop legs; the broker keeps exactly one surviving leg instead. |

`protective_legs` also appears on a **rejection** (`accepted:false`): Agora's
rollback can cancel and re-issue legs before it discovers the closing order
itself failed, so the exception (`BrokerRejectedException`, a
`BrokerUnavailableException` subclass) carries the same `protective_legs` list
via `rejectCode()`/`protectiveLegs()`. `LEG_RESTORE_FAILED_UNPROTECTED` is the
reject code that means the remainder may now sit at the broker with less
protection than it holds — Dracul raises a CRITICAL Telegram alert on that
code specifically, not on every rejection.

`closedQty`/`remainingQty` (`closed_qty`/`remaining_qty`) are unchanged in
shape but are now the values that drive Dracul's own book, `order_json` and
tool response — see `docs/wie-dracul-entscheidet.md`, "Teil-Close (TRIM):
Legs werden restauriert, nicht nur storniert", for how the executor persists
`protective_legs`/`legs_collapsed` into `executor_position` and reacts to a
rejection.
