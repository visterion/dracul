# Operations

## Deployment

- **Image**: `ghcr.io/visterion/dracul:main` (built by
  `.github/workflows/docker.yml` on pushes to `main`)
- **Topology**: co-located with Vistierie on the same Proxmox host via
  private Docker network. Dracul talks to Vistierie over the in-cluster
  URL — no TLS / mTLS in v1 (same trust boundary as HiveMem).
- **Postgres**: Dracul uses its own database/schema (`dracul`) on the
  shared Postgres instance. Flyway migrations run on container startup.
- **Telegram bot**: the Daywalker sends push alerts via a Telegram bot.
  The bot token and target chat ID are configured via env vars (see
  [Configuration](./configuration.md)).

## Environment variables

See [configuration.md](./configuration.md) for the full list.

## Building and starting the stack

The image is a multi-stage build (`java-server/Dockerfile`):
1. Node 22 Alpine builds the Vue frontend (`npm run build`, reads `.env.production` → `VITE_MOCK=false`, relative `/api/*`).
2. JDK 25 Maven builds the Spring Boot JAR, with `chronicle/dist/` copied into `src/main/resources/static/` first — the SPA is baked into the JAR as a classpath resource.
3. JRE 25 runs the JAR. Spring Boot serves `classpath:static/` and a `SpaFallbackController` rewrites non-API/non-file paths to `index.html` for Vue Router.

```bash
# Production: pull pre-built image from CI and start (Vistierie must already be running)
docker compose pull app
docker compose up -d

# Local development stack (Postgres + app, MockVistierieClient via dev profile):
docker compose up --build -d

# On LXC hosts (Proxmox/PVE) where containers can't open Unix sockets, layer the override:
docker compose -f docker-compose.yml -f docker-compose.lxc.yml up -d
```

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
