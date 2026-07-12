# 5. Building Blocks

> [!NOTE]
> **AI-Generated: this section** — inferred from the package layout, needs human review.

Two deployable modules in one monorepo.

## java-server/ — backend (Spring Boot 4, JDK 25, Flyway)

Package root `de.visterion.dracul`. Key building blocks:

| Package | Responsibility |
|---|---|
| `strigoi` | Hunter-agent domain logic and pattern implementations |
| `hunting` | Market-data hunting grounds (EDGAR/Agora filings, company data) |
| `marketdata`, `providers` | Market-data adapters and provider wiring |
| `prey`, `verdict`, `outcome` | Candidate/finding domain, decisions, tracked outcomes |
| `pattern`, `criteria` | Anomaly patterns and pre-screen criteria |
| `vistierie` | Vistierie client integration (agent runtime, tier routing) |
| `agent`, `settings`, `voievod`, `gropar`, `daywalker`, `executor` | Agent definitions, operator settings, and specialised roles |
| `chronicle`, `depot`, `watchlist`, `notes`, `report`, `notify`, `events`, `status` | Frontend-facing REST + domain support |
| `webhook`, `auth`, `config`, `error`, `stopguard` | Webhook delivery, security, cross-cutting config |

Resources: `prompts/` (Strigoi system prompts, `prompt_registry.json`),
`schemas/` (agent output schemas), `db/migration/` (Flyway V1…Vnn).

## chronicle/ — frontend (Vue 3 / Vuetify 3 / Pinia)

`src/` holds `App.vue`, the typed API client (`api/`), `components/`,
`views/`, and Pinia `stores/`. See `documentation/chronicle.md`.
