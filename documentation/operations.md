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
only trades on `saxo-sim`. Build it when the executor is first pointed at a
live connection, not before.

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

## Daywalker hours

The Daywalker StreamingBee is active only during US market hours
(configurable via `dracul.daywalker.market-open` / `market-close`).
Outside those hours it is dormant and does not consume tokens.

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

## Branch images

PRs and `slice-*` branches produce tagged images
(`ghcr.io/visterion/dracul:<branch>`). Use these for testing before
merging to `main`.
