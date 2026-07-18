# Operations

## Deployment

- **Image**: `ghcr.io/visterion/dracul:main` (built by
  `.github/workflows/docker.yml` on pushes to `main`)
- **Topology**: co-located with Vistierie on the same Proxmox host via
  private Docker network. Dracul talks to Vistierie over the in-cluster
  URL — no TLS / mTLS in v1 (same trust boundary as HiveMem).
- **Postgres**: Dracul uses its own database/schema (`dracul`) on the
  shared Postgres instance. Flyway migrations run on container startup.
- **Telegram bot**: the Daywalker sends best-effort push alerts for CRITICAL findings via a Telegram bot.
  1. Create a bot with @BotFather and copy its token → `TELEGRAM_BOT_TOKEN`.
  2. Get your chat id (e.g. message the bot, then read `https://api.telegram.org/bot<token>/getUpdates`) → `TELEGRAM_CHAT_ID`.
  3. Optionally lower `DRACUL_DAYWALKER_NOTIFY_LEVEL` to `WARNING` for more alerts.
  A blank token or chat id disables push; alerts are still persisted and visible in Chronicle.

### Post-deploy: agent definition resets (executor vetoes / kill-criteria / strigoi enrichment change set)

Deploying the executor MAX_TRANCHE/CORRELATED/kill-criteria change set, the
verdict kill-criteria watcher, and the strigoi-index/-merger/-spin
enrichment fields updates the *code defaults* for several agents' output
schemas and prompts, but `AgentDefinitionBootstrap` is insert-if-absent — an
already-registered agent's stored definition is **not** overwritten by the
new image alone (see `GenericAgentRegistrar.matches()`). After deploying
this change set, explicitly reset each affected agent's definition so
Vistierie picks up the new schema/prompt:

    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/gropar/definition/reset
    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/strigoi-index/definition/reset
    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/strigoi-merger/definition/reset
    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/strigoi-spin/definition/reset

(`gropar` — new `violated_kill_criteria` schema field + prompt;
`strigoi-index`/`strigoi-merger`/`strigoi-spin` — prompts updated to reason
over the new enrichment fields (`adv`/`marketCap`/`avgVolume20d`;
`offerPrice`/`considerationType`/`exchangeRatio`/`breakFee`/`spreadPercent`;
`distributionRatio`/`recordDate`/`distributionDate`).) Each endpoint is
behind Cloudflare Access — use [Local Access](#local-access-cloudflare-bypass)
to call it from the host. Verify via `GET /agents/<name>` on Vistierie
(`:8090`, tenant token): `output_schema`/`system_prompt` should show the
change and `version` should have bumped. The executor's `MAX_TRANCHE`/
`CORRELATED` vetoes and the verdict kill-criteria watcher (V22 migration)
need no reset — they are pure Dracul-side code, not agent definitions.

### Post-deploy: agent definition reset (executor scale-out ladder / hard-kill / GTD prompt guidance)

Deploying the trim-ladder, hard-kill-trigger, and GTD-entry-expiry change
set updates `prompts/executor.md` (three additions: scale-out ladder
guidance, a hard-kill note, and a GTD note) — same insert-if-absent caveat
as above applies to the `executor` agent definition itself. Reset it after
deploy:

    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/executor/definition/reset

Verify via `GET /agents/executor` on Vistierie (`:8090`, tenant token): the
`system_prompt` should include the new ladder/hard-kill/GTD paragraphs and
`version` should have bumped.

The same change set also alters the executor's **tool schema**: the
`place_entry` input schema gains an optional `confidence` field (number,
0–1), which the agent passes so its decision confidence lands in
`decision_log.confidence_in_decision` (the executor-side Brier /
calibration input). The definition reset above propagates this schema
change too — without it the live agent keeps the old `place_entry`
schema and every ENTER/REJECT row stays uncalibratable (`null`
confidence). Verify the reset picked it up: the `place_entry` entry in
the agent's tool catalog should list `confidence` among its properties.

This change set also bumps `DRACUL_EXECUTOR_RULE_VERSION` to `exec-v0.4`
(`RuleVersionProvider.seed()` is insert-if-absent, so the new `rule_versions`
row is seeded automatically on the next boot — no manual step). The
`executor` agent definition reset above is still required separately: the
`rule_versions` row is an audit-trail record, it does not itself push the
new prompt text to Vistierie.

### Post-deploy: agent definition reset (daywalker event-tagging / event_type change set)

Deploying the T1.3 news-event-tagging change set (shared `NewsEventType`
taxonomy + `NewsEventTagger`, NEGATIVE_NEWS gating on tagged headlines, the
nullable `daywalker_alerts.event_type` column) bumps the `daywalker` agent's
assessment schema and prompt to v1.1.0 — same insert-if-absent caveat as
above applies to the `daywalker` agent definition itself. Reset it after
deploy:

    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/daywalker/definition/reset

Before resetting, optionally check the currently registered definition —
note that reset discards any user edits made via the settings UI:

    curl -H "X-Local-Access-Token: $TOKEN" \
      http://<host-lan-ip>:8080/api/settings/agents/daywalker/definition

This step is **mandatory**, not optional: without it, the registered agent
never receives the extended schema, the LLM is never asked for `event_type`,
and the column stays silently `NULL` on every new alert — no error, no log
line, just a quietly unpopulated column. Verify via `GET /agents/daywalker`
on Vistierie (`:8090`, tenant token): `output_schema` should include the new
`event_type` field and `version` should have bumped.

### Post-deploy: agent definition reset (T2.2 portfolio-aware news implication)

Deploying T2.2 (portfolio-aware news assessment + MACRO_PORTFOLIO trigger)
updates **both** the `daywalker` and `renfield` agent prompts (direction,
weight, sector context; position block replacing `held` flag) — same
insert-if-absent caveat applies to both. After deploying this change set,
reset both agents' definitions so Vistierie picks up the new prompts:

    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/daywalker/definition/reset
    curl -H "X-Local-Access-Token: $TOKEN" -X POST \
      http://<host-lan-ip>:8080/api/settings/agents/renfield/definition/reset

Verify each agent via `GET /agents/<name>` on Vistierie (`:8090`, tenant
token): `system_prompt` should include portfolio context and `version` should
have bumped. Daywalker's prompt will contain `MACRO_PORTFOLIO` and `direction`;
renfield's will reference `position` instead of `held`.

### Prompt registry & archive

`java-server/src/main/resources/prompts/prompt_registry.json` maps every
bundled agent (`daywalker`, `executor`, `gropar`, `strigoi-echo`,
`strigoi-index`, `strigoi-insider`, `strigoi-lazarus`, `strigoi-merger`,
`strigoi-spin`, `voievod`, `voievod-outcome`) to the `version` and `body_hash` its current
`prompts/<agent>.md` file is expected to have (`body_hash` =
`"p-" + sha256(body).substring(0, 12)`, the same derivation the runtime
`agent_version` uses — see `PromptHashes` in `de.visterion.dracul.agent`).

**Editing a prompt (version bump workflow):**

1. Before editing, copy the current `prompts/<agent>.md` unchanged to
   `prompts/archive/<agent>/<old-version>.md` (see
   `prompts/archive/README.md`). This is a source-tree convention, not a
   runtime dependency — nothing reads `archive/` at startup.
2. Edit the live file: change the body and bump the `version:` field in its
   `<!-- agent-meta -->` header.
3. Update `prompt_registry.json`: bump that agent's `version` to match, and
   recompute `body_hash` from the new body.
4. `PromptRegistryTest` fails the build if steps 2–3 drift apart — it is the
   CI guard that forces a registry bump alongside every prompt edit.
5. Deploy, then follow the usual definition-reset step (see above) so the DB
   row and Vistierie pick up the new prompt — the registry only tracks what's
   *bundled in the jar*, not what's live in the DB.

**Bootstrap validation:** `PromptRegistryValidator` runs once on
`ApplicationReadyEvent` and, for every enabled agent, compares the bundled
`prompts/<agent>.md` (header version + body hash) against the registry, and
separately compares the DB-stored `prompt_text` hash against the registry.
A registry-vs-file mismatch (missing registry entry, version mismatch, or
hash mismatch) is a hard error: it's logged as a WARN and recorded in
`app_settings` under key `health.prompt_registry` as
`MISMATCH:<agent1,agent2,...>` (or `OK` if everything lines up). A
DB-vs-registry hash mismatch alone is only logged at INFO and does **not**
set the health flag — a user-edited prompt in the DB is legitimate and not
itself a problem; it will show as "prompt file changed" only if it also
disagrees with the *bundled file*.

### Prompt change discipline

Every prompt edit follows the **version bump workflow** in
[Prompt registry & archive](#prompt-registry--archive) above — archive the
*unchanged current* file first, then edit the body and bump the header
`version:`, then update `prompt_registry.json` (`PromptRegistryTest` is the
CI guard). This section adds only the deploy-side discipline on top:

1. **Deploy.** Push to `main` (CI builds the image, runs e2e) and pull the
   new image to production. The bundled `prompts/<agent>.md` and registry
   are now current in the container, but the **database row is not yet
   updated** (bootstrap is insert-if-absent).

2. **Call the definition-reset endpoint** to propagate the new prompt to
   the DB row and Vistierie:

   ```
   curl -H "X-Local-Access-Token: $TOKEN" -X POST \
     http://<host-lan-ip>:8080/api/settings/agents/<name>/definition/reset
   ```

   The app log records the hash transition:
   `agent <name> definition reset: prompt <old-hash> -> <new-hash>`
   (the same line, with `updated` instead of `reset`, is logged when a
   definition is edited via `PUT .../definition` / the settings UI).
   Verify via `GET /agents/<name>` on Vistierie (`:8090`, tenant token) that
   `system_prompt` now contains your edits and `version` has bumped.

3. **Historical hashes stay valid.** Old `agent_version` hashes (from before
   the change) remain valid references on historical signals and in the run
   audit trail — never rewrite them. Outcome metrics are **version-scoped**:
   compare results only within a single prompt version; pooling signals from
   different prompt versions produces a meaningless blended number.

### Rule-version change discipline

`rule_versions` is an append-only audit trail — `RuleVersionProvider.seed()`
only ever inserts a new row when the configured version is absent; existing
rows (e.g. `exec-v0.3`) are never edited or deleted. Follow these rules when
touching executor thresholds, guards, or prompt wording:

- **One change per `rule_version`.** Bundle a single conceptual change
  (one threshold tweak, one new guard, one prompt addition) into each
  version bump, not several unrelated changes at once — this keeps any
  later calibration delta attributable to a single cause.
- **Rollback is a new version, not an edit.** If a version regresses,
  bump `DRACUL_EXECUTOR_RULE_VERSION` again with the reverted params
  rather than mutating the row for the version being rolled back — history
  stays monotonic and every `decision_log.rule_version` value keeps
  pointing at whatever was actually active when that decision was made.
- **Compare metrics only within a version.** `decision_log.rule_version`
  tags every row, so `GET /api/executor/calibration` /
  `GET /api/executor/behavior` results must be read per `rule_version` —
  pooling decisions from `exec-v0.3` and `exec-v0.4` together produces a
  meaningless blended number.
- **Minimum sample before judging a version:** wait for at least 2 weeks
  of sim runtime **and** at least 20 decisions under that version before
  drawing any conclusion from its calibration numbers; below that,
  small-sample noise dominates.

`exec-v0.4` (this change set) also fixes an audit-trail drift: the seeded
`rule_versions.params.confidence_min` was `0.6` while the actual runtime
`dracul.executor.min-confidence` default had already moved to `0.65` in an
earlier change — only the audit blob was stale, not the enforced veto
behavior. `exec-v0.4` records the correct `0.65` plus the knobs introduced
this branch (`trim_fractions`, `entry_gtd_days`, `kill_criteria_hard`).

### Human-confirm gate (deferred to live phase)

A human-confirmation step in front of broker-write calls (`place_entry`,
`exit_position`, `add_tranche`) is a live-trading-phase feature and is
**not implemented in sim.** `OrderGuard`'s `NON_SIM_CONNECTION` reject reason
is the guard such a step would hang off, but `dracul.executor.connection` is
always the paper/sim connection today, so that branch cannot fire — there is
no human-confirm UI or webhook yet, and none is needed while the executor
only trades on `depot-1` (the neutral sim connection id, formerly
`saxo-sim`). Build it when the executor is first pointed at a
live connection, not before.

### Deploying the depots view (Agora readonly live tokens + `depot-1` rename)

The `/depots` view (`GET /api/depots` + instrument bundle) reads live broker
data through a dedicated read-only Agora client
(`documentation/configuration.md#depots-positions-view`). Bringing it
up requires **two coordinated deploys, in this order** — Agora first, then
Dracul:

1. **Agora deploy:**
   - Set `AGORA_TRADING_LIVE_TOKENS_READONLY=<token>` (comma-separated if
     more than one) — the read-only token(s) accepted for account/positions/
     orders/list-connections reads on **live** connections. These tokens must
     never be accepted on any order-placing/modifying tool call (Agora
     enforces this; see `feat/live-readonly-tokens`).
   - Rename the `saxo-sim` connection key to `depot-1` in Agora's
     `agora.trading.connections` config (same rename Dracul's V25 migration
     applies on its own side — see below). This is a config-only change
     (the connection's `provider`/`environment`/`base-url`/credentials are
     unchanged, only the map key + any `AGORA_TRADING_SAXO_SIM_*` env names
     that encode it) and must land **before** Dracul's V25 migration runs,
     or the two sides briefly disagree on the connection id.
   - Restart/redeploy Agora so both changes are live.

2. **Dracul deploy:**
   - Set `DRACUL_DEPOTS_AGORA_READONLY_TOKEN=<same token as above>` (see
     `documentation/configuration.md`).
   - If not relying on the new default, also set
     `DRACUL_EXECUTOR_CONNECTION` explicitly — it now defaults to `depot-1`
     (renamed from `saxo-sim`).
   - On startup, Flyway migration **V25** auto-renames any persisted rows
     referencing the old `saxo-sim` connection id (e.g.
     `executor_position.connection`) to `depot-1`. No manual data migration
     step is needed.

**Verify:** `GET /api/depots` (through Cloudflare Access, or Local Access —
see below) returns the expected connections; live-environment connections
appear only when the calling user's email is on
`DRACUL_DEPOTS_LIVE_VISIBLE_EMAILS`. Confirm the executor's reconciliation
still matches: `executor_position.connection` should read `depot-1`, matching
whatever Agora now reports as the live connection id.

**Rollback note:** V25 is a **one-way, idempotent-forward** rename — it does
not ship a `.undo` migration. Reverting the connection name (e.g. rolling
back to a pre-rename Agora config) requires manually running the reverse
`UPDATE ... SET connection = 'saxo-sim' WHERE connection = 'depot-1'` against
every affected table, and reverting the Agora connection-key config at the
same time so the two sides stay in agreement. Do not roll back one side
without the other.

## Environment variables

See [configuration.md](./configuration.md) for the full list.

### Cloudflare Access (authentication)

| Variable | Description |
|---|---|
| `DRACUL_CLOUDFLARE_TEAM_DOMAIN` | Your Cloudflare Access team URL, e.g. `https://<team>.cloudflareaccess.com`. Used to fetch the JWKS for JWT verification. |
| `DRACUL_CLOUDFLARE_AUD` | The Access application audience tag (shown in the Access application settings). Dracul validates the `aud` claim in every incoming JWT against this value. |
| `DRACUL_PRIMARY_USER_EMAIL` | Email address of the primary operator. On startup, any watchlist rows with `user_id = 'default'` are reassigned to this address so that legacy data has a proper owner after the auth migration. |

**Important:** In production the app **must** sit behind Cloudflare Access. The
JWT verification makes header spoofing moot, but the Access policy is what
gates which users may log in. If `DRACUL_CLOUDFLARE_TEAM_DOMAIN` or
`DRACUL_CLOUDFLARE_AUD` is blank outside a `dev` or `test` Spring profile,
**the application refuses to start (fail-closed)**. Under the `dev` or `test`
profiles a blank CF config enables bypass mode instead: an `X-Dev-User` request
header sets the current user (fallback: `default`).

## Local access (Cloudflare-bypass)

Operator-facing endpoints sit behind Cloudflare Access. For automation (curl, headless browser)
that reaches Dracul **directly** (not through Cloudflare — e.g. `http://<host-lan-ip>:8080`), an
opt-in local-access path bypasses the Cloudflare JWT check when a shared secret is presented.

**Off by default.** Enable by setting BOTH (a blank token keeps it disabled, fail-safe):

    DRACUL_LOCAL_ACCESS_ENABLED=true
    DRACUL_LOCAL_ACCESS_TOKEN=<a-strong-secret>

A request authenticated this way acts as `dracul.primary-user-email` (same identity as a
Cloudflare login).

- **curl / programmatic (preferred for automation):** send the token in the `X-Local-Access-Token` header:

      curl -H "X-Local-Access-Token: $TOKEN" -X POST \
        http://<host-lan-ip>:8080/api/settings/agents/gropar/definition/reset

- **Browser / Playwright:** navigate once to `http://<host-lan-ip>:8080/?lat=$TOKEN`. The server
  sets an HttpOnly `DRACUL_LAT` cookie and 302-redirects to `/` (token stripped from the URL);
  subsequent same-origin requests carry the cookie.

**Security:**

- The cookie is `HttpOnly` + `SameSite=Lax`; `Secure` is set only when the request itself is over
  https. The direct route is typically plain http over the LAN/tailscale and is not
  internet-exposed (Cloudflare fronts the public hostname; `:8080` direct is reachable only on the
  local network).
- Prefer the header (or cookie) over the `?lat=` query param for non-interactive use: a query-param
  token is recorded in reverse-proxy / servlet access logs. The browser bootstrap accepts it for
  convenience and immediately exchanges it for the cookie + a clean URL.
- The attack surface widens only to a party that can both reach `:8080` directly AND holds the
  token. Leave it off unless needed; enabling requires a container restart.

## Building and starting the stack

The image is a multi-stage build (`java-server/Dockerfile`):
1. Node 22 Alpine builds the Vue frontend (`npm run build`, reads `.env.production` → `VITE_MOCK=false`, relative `/api/*`).
2. JDK 25 Maven builds the Spring Boot JAR, with `chronicle/dist/` copied into `src/main/resources/static/` first — the SPA is baked into the JAR as a classpath resource.
3. JRE 25 runs the JAR. Spring Boot serves `classpath:static/` and a `SpaFallbackController` rewrites non-API/non-file paths to `index.html` for Vue Router.

```bash
# Pull the pre-built CI image (Vistierie must already be running):
docker compose pull app

# Dev profile (MockVistierieClient) — local frontend work, no real Vistierie needed:
docker compose up --build -d

# On LXC hosts (Proxmox/PVE) where containers can't open Unix sockets, layer the override:
docker compose -f docker-compose.yml -f docker-compose.lxc.yml up -d

# Co-located with the real Vistierie on the same LXC host (Proxmox/PVE):
# requires a local .env (see .env.example) and Vistierie already running on hivemem-net.
docker compose -f docker-compose.yml -f docker-compose.lxc.yml -f docker-compose.host.yml up -d
```

The base `docker-compose.yml` alone runs the dev profile (MockVistierieClient) for local frontend work; the `docker-compose.host.yml` override flips to the real Vistierie.

The `app` container exposes port 8080. Override the host port via `DRACUL_PORT`
and the image tag via `DRACUL_IMAGE` (defaults: `8080` and
`ghcr.io/visterion/dracul:main`).

## Kill switch

Dracul has no kill switch of its own. To stop all Strigoi and Daywalker
activity, flip the kill switch on the `dracul` tenant in Vistierie:

```
POST http://vistierie:8090/api/tenants/dracul/kill
Authorization: Bearer <vistierie-admin-token>
```

The next cron tick is suppressed until the switch is released.
Alternatively, use the Admin section in Dracul's own Settings view
(`POST /api/admin/kill`), which proxies the same call.

## Daywalker session window

The Daywalker StreamingBee opens once per business day at a fixed UTC
cron schedule and runs for a fixed-duration session window (16 hours by
default), covering US market hours end-to-end. Outside the session it is
dormant and does not consume tokens.

**Defaults:**
- Session cron: `0 0 8 * * 1-5` UTC (Monday–Friday, 08:00 UTC)
- Session duration: `57600` seconds (16 hours)
- Summer ET: 04:00–20:00 EDT (market open through after-hours close)
- Winter ET: 03:00–19:00 EST (market open through after-hours close)

**DST caveat:** The session cron is a fixed UTC expression with no DST
handling — Vistierie crons are calendar-blind. On the EST/EDT boundary
(second Sunday in March, first Sunday in November) the session open
drifts ~1 hour relative to US market hours. This is an accepted trade-off
for simplicity; calendar-aware scheduling is deferred.

## Poll budget sizing

The Daywalker event-detect poll wraps multiple symbol lookups, news
fetches, and price checks inside a `dracul.daywalker.poll-budget-ms`
timeout (default 60000 ms = 60 seconds). If one poll cycle exceeds the
budget, unfinished symbols are skipped for that poll with one WARN log
including the count; the next poll starts fresh.

**Budget sizing for steady-state:**

The news window is whole-UTC-day granular and the 1-hour per-(symbol,
trigger_type) cooldown expires ~15 times per 16-hour session. One
persistent tagged headline can thus yield ~15 LLM runs per day per
symbol per trigger type. A symbol can fire up to ~5 independent trigger
types (PRICE_SPIKE, VOLUME_SPIKE, NEGATIVE_NEWS, RECOMMENDATION_CHANGE,
FORM_4), so worst-case steady-state budget demand ≈ **universe size ×
15 × 5 runs/day**.

If steady-state cost proves too high (e.g. in production with a large
watchlist + depot union), increase the per-symbol cooldown via
`DRACUL_DAYWALKER_COOLDOWN` (env variable, default 3600 s = 60 min; no
code change needed). Alternatively, cap Vistierie's budget for the
`daywalker` tenant to force automatic run skipping when token spend
approaches the daily limit.

## Daywalker prod activation checklist

Before deploying to production, configure:

1. **Session schedule + duration (explicit, even though they match defaults):**
   ```
   DRACUL_DAYWALKER_SESSION_CRON="0 0 8 * * 1-5"
   DRACUL_DAYWALKER_SESSION_DURATION=57600
   ```
   This makes the production config self-describing and matches the
   codebase defaults.

2. **Enable + token:**
   ```
   DRACUL_DAYWALKER_ENABLED=true
   DRACUL_DAYWALKER_TOKEN=<random-bearer-token>
   ```
   Replace `dev-token-change-me` in `application.yaml`.

3. **Telegram (same credentials as gropar/morning-report):**
   ```
   TELEGRAM_BOT_TOKEN=<bot-token-from-BotFather>
   TELEGRAM_CHAT_ID=<your-chat-id>
   DRACUL_DAYWALKER_NOTIFY_LEVEL=CRITICAL
   ```
   Keep `notify-level` at CRITICAL for production; set to WARNING if more
   verbosity is desired. Test with `@BotFather` to create a bot, send it
   one message, then read the chat id from `https://api.telegram.org/bot<token>/getUpdates`.

4. **Vistierie budget caps:**
   Set daily and monthly budget caps for both `daywalker` and `renfield`
   agents via Vistierie admin API to avoid runaway token spend in
   production. See the Agent budget guard section below for the procedure.

## Renfield (daily watchlist-review agent)

Renfield runs once per business day at 12:00 UTC (≈ 08:00 ET summer /
07:00 ET winter), analyzes the primary user's watchlist (each symbol
flagged `held` when it is also an open depot position; depot-only
positions not on the watchlist are not reviewed), and emits concrete
trade proposals to Telegram and the SSE stream. It is trigger-only (no
recursion), produces proposals only (never places orders), and respects
the operator's read-only doctrine.

**Enable + configure:**

```
DRACUL_RENFIELD_ENABLED=true
DRACUL_RENFIELD_CRON="0 0 12 * * MON-FRI"        # 12:00 UTC weekdays
DRACUL_RENFIELD_TOKEN=<random-bearer-token>     # backs both completion-webhook token and inbound webhook verification
```

If the watchlist is empty, the scheduler skips the run entirely (INFO log,
no Vistierie trigger, no Telegram). A completed run with an empty proposals
list still sends a "keine Vorschläge heute" Telegram digest (no SSE event
in that case). Completions are idempotent: retried webhook deliveries
insert zero rows and send no duplicate Telegram/SSE messages. Proposals
are bundled into one Telegram push per run and published to the SSE stream
as a single bundled `proposal.new` event per completed run (`{count, run_id,
ts}`), not one event per proposal.

Renfield also requires a Vistierie budget (same procedure as daywalker —
see Agent budget guard section below).

## Rollout order (critical)

**The bootstrap is insert-if-absent**: agent definitions are not
overwritten on deploy, so schedule/duration changes in the code default
never reach the already-stored definition without an explicit reset. This
ordering is mandatory:

1. **Merge + push** to `main`. CI builds the image
   (`ghcr.io/visterion/dracul:main`) and runs e2e.

2. **Deploy** the new image (pull on LXC / restart `app` service).
   Flyway V35 migration runs (adds `trade_proposals` table, Renfield
   schema seeding). Daywalker + Renfield agent-definition defaults are
   bundled in the JAR.

3. **MANDATORY: Delete the daywalker agent definition row from the DB:**
   ```sql
   DELETE FROM agent_definition WHERE name = 'daywalker';
   ```
   Restart the `app` container. `AgentDefinitionBootstrap` re-inserts
   with the new schedule (`0 0 8 * * 1-5`) and session-duration (`57600`).
   Renfield is a fresh insert (always inserted, never needs reset).

4. **Verify the reset:**
   ```sql
   SELECT name, schedule, session_duration_seconds 
   FROM agent_definition 
   WHERE name = 'daywalker';
   ```
   Expected: `daywalker | 0 0 8 * * 1-5 | 57600`

5. **Set production environment variables** (see Daywalker prod
   activation checklist + Renfield config above).

6. **Set Vistierie budget caps** for both `daywalker` and `renfield`
   agents via admin API (Agent budget guard section, or Vistierie docs).

7. **Verify in production:**
   - Daywalker session opens at 08:00 UTC
   - At least one watchlist + depot symbol appears in the first poll
   - Renfield fires at 12:00 UTC with the daily report
   - Telegram messages arrive (both Daywalker + Renfield)

## Symbol-namespace verify-before-build

Before relying on the depot ∪ watchlist union in production, confirm
that depot `get_positions` symbols and watchlist tickers share one
namespace (e.g. both use `SAP` or both use `SAP.DE`, not a mix). Run
one production check from the Depots view or via a direct Agora call to
the live connection, and compare against a watchlist export. If they
differ, normalize the symbols in one side before the union and pin the
canonical form in a test (follow-up change, out of scope for this
deploy).

## Backups

Nightly `pg_dump` of the `dracul` database, retained alongside HiveMem /
Vistierie backups. The `dracul.prey` and `dracul.verdicts` tables are
append-only; no data is ever mutated (except Voievod writing outcome
columns to `prey`). Standard Postgres backup / restore is sufficient.

## Monitoring

- **Vistierie cost dashboard**: proxied through Dracul's Vistierie view
  (`/vistierie`) — shows per-agent token usage and budget status.
- **Run history**: Vistierie's Run History API (`/api/cost/runs`).
- **Application logs**: structured JSON to stdout; collect via Docker log
  driver. Key events: Strigoi run start/end, Prey written, Verdict
  created, Daywalker trigger, Telegram notification sent.

## Agent budget guard

A scheduled agent without a Vistierie budget silently never runs: Vistierie
throws `BudgetException: agent budget missing` on any pause/unpause toggle, and
`GET /agents/{name}/budget` returns `200` with all-null caps, which masks the
problem (this bit prod once — Gropar stayed stuck paused and never ran).

`AgentBudgetGuard` (`de.visterion.dracul.agent`) runs once at startup
(`ApplicationReadyEvent`) and checks every enabled, scheduled agent definition
(`schedule() != null`) against Vistierie's `GET /agents/{name}/budget`. An
agent counts as missing a budget when the call fails, or when both
`dailyCapMicros` and `monthlyCapMicros` come back null. The result is written
to the `health.agent_budgets` app setting (`OK` or `MISSING:<name>,<name>,...`)
and logged as a `WARN` per affected agent. `SettingsController` reads that
flag to set `budgetMissing` on the corresponding `AgentConfigRow`, and the
Chronicle Settings → Agents view renders a "no budget" warning chip next to
the affected row.

To fix a flagged agent, set its budget via Vistierie's admin endpoint — see
the budget-setup procedure for scheduled agents in this repo's project notes
(agent schema/prompt changes + budgets section). The guard only re-evaluates
at the next application startup; after fixing the budget, restart the
`dracul` container (or wait for the next deploy) to clear the flag.

**`voievod-outcome` deploy note:** like every scheduled agent, it needs a
Vistierie budget set once before it can be unpaused — the definition alone
(inserted by `AgentDefinitionBootstrap`) is not enough. Mirror `voievod`'s
budget ($1/day, $10/month) via the same admin-endpoint procedure referenced
above (agent schema/prompt changes + budgets section), substituting
`voievod-outcome` for the agent name:
```
curl -s -X PATCH -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" \
  -d '{"daily_cap_micros":1000000,"monthly_cap_micros":10000000}' \
  http://localhost:8090/admin/tenants/dracul/agents/voievod-outcome/budget
```

**`daywalker-deep` deploy note:** it is trigger-only (`schedule=null`), so
`AgentBudgetGuard`'s scheduled-agent scan does **not** flag it — but Vistierie's
`RunController.trigger` still calls `BudgetEnforcer.checkOrThrow` on *every*
`POST /agents/{name}/run`, scheduled or not, so a missing budget makes every
escalation trigger fail (silently, from `DaywalkerCompletionService`'s
try/catch — see `documentation/strigoi.md`'s escalation section) with no health
chip to warn you. Set a budget once via the same admin-endpoint procedure,
substituting `daywalker-deep` for the agent name:
```
curl -s -X PATCH -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" \
  -d '{"daily_cap_micros":1000000,"monthly_cap_micros":10000000}' \
  http://localhost:8090/admin/tenants/dracul/agents/daywalker-deep/budget
```

## Morning report digest

To enable the daily Telegram morning-report digest:

1. Ensure Telegram is already configured for gropar (i.e. `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are set — same credentials reused).
2. Set `DRACUL_REPORT_MORNING_ENABLED=true` (default `false`).
3. Optionally override the send time via `DRACUL_REPORT_MORNING_CRON` (default `0 0 7 * * 1-5`, zone Europe/Berlin).

The digest is a **Dracul-internal cron** — it does **not** register a Vistierie agent. No Vistierie budget change or agent reset is required (unlike a new scheduled Strigoi or Gropar itself).

## Stop-proximity watcher

To enable the intraday stop-proximity watcher in production:

1. Ensure Telegram is already configured (same `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` as gropar / morning report — no new credentials needed).
2. Add `DRACUL_STOPGUARD_ENABLED=true` to `docker-compose.host.yml` under `app.environment`.
3. Recreate the container (`docker compose ... up -d app`).

The watcher is a **Dracul-internal cron** (`StopProximityWatcher`, `@Scheduled`). It is **not** a Vistierie agent — no Vistierie budget, no `definition/reset`, and the Vistierie kill switch does not affect it (unlike gropar, Daywalker, and the Strigoi). To stop it in prod, set `DRACUL_STOPGUARD_ENABLED=false` (or remove the env var) and recreate the container.

Optionally tune the proximity band width and Telegram verbosity:

    DRACUL_STOPGUARD_ATR_MULTIPLE=0.5          # width of proximity zone (fraction of ATR)
    DRACUL_STOPGUARD_NOTIFY_LEVEL=WARNING       # WARNING = both proximity + breach; CRITICAL = breach only

See [configuration.md](./configuration.md) for the full knob reference.

## Pattern gates (T3.3)

**Approve = enforce immediately.** Approving a `PENDING` pattern that carries a
stored `gate` predicate flips it to `ACTIVE` and the gate is enforced on the
very next signal evaluation — the veto catalog runs `PATTERN_GATE` (position
#12, after `REDUNDANCY`, before `LIQUIDITY`) against every candidate entry. A
matching signal is vetoed with reason `PATTERN_GATE` and a detail string
`pattern_gate:<id> (<name>)`. `PATTERN_GATE` is one of the **transient** reject
reasons (`RejectReason.isTransient()`): a blocked signal is not disqualified —
it stays `PENDING` and is retried on subsequent executor runs until it either
passes or hits `SIGNAL_EXPIRED` (max signal age, default 5 trading days).

**Recovering from a bad gate.** If a gate turns out to be mistranslated or too
broad, the operator has two levers: deactivate the whole pattern (`reject`/
`deactivate` action, both flip status to `REJECTED`), or edit just the gate
via `update_gate` (`PATCH /api/patterns/{id}`,
see `documentation/api.md`). The Chronicle gate editor (`PatternGateEditor`) is
available both on `PENDING` cards and on expanded `ACTIVE` rows — editing an
armed gate is the operator's main correction lever, since an `ACTIVE` pattern
can't be "unapproved" back to `PENDING`. Once the gate is cleared or
corrected, the still-`PENDING` signals it was blocking flow again on the next
run.

**Honest limitation (spec D5).** An `ACTIVE` gate blocks the trades it matches
*before* they are ever placed, so its own machine-checkable supporting
evidence can only ever grow from **pre-approve** trade history (the weekly
scorer matches gated patterns against completed `TRADE` outcomes) — an
enforced gate can never accumulate new supporting/refuting evidence from
trades it itself prevented. The gate's *ongoing* effect is visible instead as
`blockedCount` in the pattern API/UI: `COUNT(DISTINCT signal_id)` over
`decision_log` rows whose `reason_code` is `PATTERN_GATE`, computed at read
time (not persisted). This has a first-match attribution bias: when two or
more `ACTIVE` gates could match the same signal, `VetoService` records the
detail of only the **first** matching gate in repository order — so with
overlapping gates, later gates can show `blockedCount = 0` even though they
would also have matched every one of those blocked signals.

**`update_gate` and evidence.** `update_gate` deletes the pattern's
machine-scored auto-evidence (`pattern_evidence` rows with `outcome_ref IS NOT
NULL`) and clears the runtime-derived aggregates (`supported_count`,
`avg_uplift_percent`, but not the LLM-seeded `evidence_count`, which is
protected by a `GREATEST` floor) in the same transaction as the gate replace.
The next weekly scorer full rescan rebuilds both under the new predicate — no
watermark, so this is a full rescan every run and self-heals for free. If a
gate edit lands mid-way through a running scorer batch, a few old-predicate
evidence rows can be left behind until the next weekly rescan; this is
accepted (no locking between the edit and the scorer).

**Rollout note.** Deploying the `voievod-outcome` prompt/schema bump (v1.1.0,
adding the optional `suggested_gate` output field) requires the same
insert-if-absent reset as every other agent-definition change:

    DELETE FROM agent_definition WHERE name = 'voievod-outcome';

then restart the `app` container so `AgentDefinitionBootstrap` re-seeds the
row with prompt v1.1.0 and the `suggested_gate` schema. `daywalker` and
`renfield` are untouched by this bump. Verify via `GET /agents/voievod-outcome`
on Vistierie (`:8090`, tenant token): `version` should read `1.1.0`. One
staging run must additionally verify Vistierie passes the optional
`suggested_gate` structured-output field through end to end before relying on
it in production (not unit-testable — see the spec's Testing section, D3
must-verify).

**Scorer operations.** `PatternOutcomeScorer` runs weekly (default Saturday
06:00 UTC, one hour before the Saturday `voievod-outcome` run at 07:00 UTC —
see `documentation/configuration.md`). Each run is a full rescan (no
watermark) over every completed `TRADE` outcome against every gated
`PENDING`/`ACTIVE` pattern; a single outcome's match/insert failure is caught
and logged per-outcome so one bad row never aborts the whole run. Outcomes
with a structurally null `realized_r`/`entry_price`/`initial_stop`, or a
zero `entry_price`/r-per-share, are skipped at DEBUG (a defined skip, not an
error). Idempotency is the `(pattern_id, outcome_ref)` partial unique index on
`pattern_evidence` (`ON CONFLICT DO NOTHING`), so re-running the scorer over
already-scored outcomes is a no-op.

**Boundary:** gates bite at **place-entry only**. `add-tranche` (tranche-2
adds to an already-open position) runs `CapitalBounds` checks only — it does
not re-run the veto catalog, so an `ACTIVE` gate cannot block a size-up on a
position that already cleared entry.

## Branch images

PRs and `slice-*` branches produce tagged images
(`ghcr.io/visterion/dracul:<branch>`). Use these for testing before
merging to `main`.
