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

## Branch images

PRs and `slice-*` branches produce tagged images
(`ghcr.io/visterion/dracul:<branch>`). Use these for testing before
merging to `main`.
