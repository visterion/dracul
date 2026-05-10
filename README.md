# Dracul

> **An autonomous investment-research system. Six specialised hunters scan the market every night for documented anomalies and surface candidates for human review.**

Dracul runs a roster of **Strigoi** — agentic hunters, each tuned to one
academically-documented anomaly (spin-offs, insider clusters,
post-earnings drift, quality-at-52w-low, index-inclusion drift, M&A
arbitrage). They sleep through the trading day, wake at night, scan
their hunting grounds, and lay out what they found in the Chronicle by
morning. The operator decides what, if anything, to do with the
findings.

[![docker](https://github.com/visterion/dracul/actions/workflows/docker.yml/badge.svg)](https://github.com/visterion/dracul/actions/workflows/docker.yml)
[![test](https://github.com/visterion/dracul/actions/workflows/test.yml/badge.svg)](https://github.com/visterion/dracul/actions/workflows/test.yml)
[![codeql](https://github.com/visterion/dracul/actions/workflows/codeql.yml/badge.svg)](https://github.com/visterion/dracul/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/visterion/dracul/graph/badge.svg)](https://codecov.io/gh/visterion/dracul)
[![lines of code](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/visterion/dracul/main/badges/loc.json&cacheSeconds=300)](https://github.com/visterion/dracul)
[![License: Sustainable Use](https://img.shields.io/badge/license-Sustainable--Use-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-25-blue)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/spring%20boot-4.0-6DB33F)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/postgresql-17-336791)](https://postgresql.org)
[![Vue](https://img.shields.io/badge/vue-3-42B883)](https://vuejs.org)

**Docker image:** [`ghcr.io/visterion/dracul:main`](https://github.com/visterion/dracul/pkgs/container/dracul)

> **Not investment advice. Not an auto-trader. Read-only research
> assistant for self-hosted, single-operator use.**

---

## Why Dracul exists

Most investment software either chases generic factors (Value,
Momentum, Quality) or wraps a chatbot around a price feed. Neither
matches what the academic literature actually says about where edge
lives: in **specific, structural anomalies** where institutional
participants systematically misprice — spin-offs forced out of index
funds, insider clusters preceding news, post-earnings drift after
surprises, quality companies hitting 52-week lows for non-fundamental
reasons.

These setups don't appear daily. A patient hunter sees one or two per
month. Dracul's design takes that seriously: the Strigoi are
unhurried, the system surfaces candidates rarely and confidently, and
nothing is automated past the candidate stage.

Existence proof: Sandisk was spun off from Western Digital in February
2025. The classic Greenblatt spin-off setup. Plus 3000% in the months
that followed. A Strigoi tuned to that anomaly should have flagged it
before the move was obvious.

---

## How it works

```
                ┌──────────────────────────────────────┐
                │              Vistierie               │
                │   (agent runtime, this repo's only   │
                │      external runtime dependency)    │
                └──────────────┬───────────────────────┘
                               │ register agents,
                               │ tool webhooks,
                               │ tier-based routing
                               ▼
┌────────────────────────────────────────────────────────────────┐
│                            Dracul                               │
│                                                                 │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│   │ Strigoi- │  │ Strigoi- │  │ Strigoi- │  │ Strigoi- │ ...   │
│   │   Spin   │  │ Insider  │  │   Echo   │  │ Lazarus  │       │
│   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│        │             │             │             │             │
│        ▼             ▼             ▼             ▼             │
│   ┌────────────────────────────────────────────────────┐       │
│   │           Hunting grounds (adapters)               │       │
│   │   EDGAR · prices · news · earnings calendar        │       │
│   └────────────────────────────────────────────────────┘       │
│        │             │             │             │             │
│        └─────────────┴──────┬──────┴─────────────┘             │
│                             ▼                                   │
│                    ┌─────────────────┐                          │
│                    │  Crypt (PG)     │ append-only prey/verdict │
│                    └────────┬────────┘                          │
│                             ▼                                   │
│                    ┌─────────────────┐                          │
│                    │   Chronicle     │   Vue 3 / Vuetify 3      │
│                    └─────────────────┘                          │
└────────────────────────────────────────────────────────────────┘
```

Vistierie owns the agent runtime (scheduling, recursion, context
shielding, cost ledger, kill switch, tier-based routing). Dracul owns
the investment domain (Strigoi logic, market-data adapters,
pre-screen, prey/verdict persistence, Chronicle frontend). The split
is non-negotiable — see `CLAUDE.md`.

---

## The Strigoi

| # | Strigoi | Anomaly | Tier | Source |
|---|---------|---------|------|--------|
| 1 | **Strigoi-Spin** | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | **Strigoi-Insider** | Insider cluster buys | routine | Lakonishok & Lee 2001 |
| 3 | **Strigoi-Echo** | Post-earnings drift (PEAD) | routine | Bernard & Thomas 1989 |
| 4 | **Strigoi-Lazarus** | Quality at 52-week low | reasoning | Piotroski 2000 |
| 5 | **Strigoi-Index** | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | **Strigoi-Merger** | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |

Each Strigoi follows the same shape: deterministic pre-screen against
its hunting ground → LLM evaluation via Vistierie at the appropriate
tier → structured `Prey` JSON written to the Crypt. The cheap routine
ones run on Haiku-class models; the dense reasoning ones get Sonnet.
Switching tiers is a Vistierie routing-rule edit, never a code change.

When two or more Strigoi flag the same instrument independently,
Dracul produces a **Verdict** — a synthesised summary of why the
consensus formed and what the differing perspectives saw.

---

## Quick start

> Dracul depends on a running [Vistierie](https://github.com/visterion/vistierie)
> instance and a Postgres database. Bring those up first.

```bash
docker run --rm -p 8091:8091 \
  -e DRACUL_DB_URL=jdbc:postgresql://host.docker.internal:5432/dracul \
  -e DRACUL_DB_USER=dracul \
  -e DRACUL_DB_PASSWORD=dracul \
  -e DRACUL_VISTIERIE_BASE_URL=http://vistierie:8090 \
  -e DRACUL_VISTIERIE_TOKEN='<bearer-token-issued-by-vistierie>' \
  -e DRACUL_TOOL_WEBHOOK_TOKEN='<dracul-side-secret>' \
  -e EDGAR_USER_AGENT='Dracul research@example.com' \
  ghcr.io/visterion/dracul:main
```

Bootstrapping the `dracul` tenant inside Vistierie, seeding the
Strigoi roster, and the operations runbook live in
[`documentation/operations.md`](documentation/operations.md).

---

## Documentation

| | |
|---|---|
| [strigoi.md](documentation/strigoi.md) | Strigoi roster, the per-hunter pattern, tier choices |
| [hunting-grounds.md](documentation/hunting-grounds.md) | Market-data adapters (EDGAR, prices, news, calendar) |
| [vistierie-integration.md](documentation/vistierie-integration.md) | How Dracul registers agents and tool webhooks with Vistierie |
| [api.md](documentation/api.md) | REST endpoint reference |
| [architecture.md](documentation/architecture.md) | System overview, modules, data model |
| [configuration.md](documentation/configuration.md) | All `dracul.*` properties and env vars |
| [chronicle.md](documentation/chronicle.md) | Vue 3 frontend, planned views |
| [operations.md](documentation/operations.md) | Tenant setup, kill switch, backups |

---

## Build from source

Requires JDK 25, Node 22+, and Docker (for the Postgres testcontainer
used in tests).

```bash
export JAVA_HOME=/path/to/jdk-25
cd java-server
./mvnw test                        # full suite
./mvnw -DskipTests package
java -jar target/dracul-1.0.0.jar
```

---

## Project values

- **Vistierie is upstream.** If Dracul wants a feature from the agent
  runtime, it goes into Vistierie, not into a Dracul-side patch. Dracul
  is the second consumer that validates Vistierie's API; layering
  violations defeat that purpose.
- **No domain leaks into Vistierie.** Vistierie sees opaque `tenant`,
  `realm`, `purpose`, `messages`, `payload`. Investment terms in
  Vistierie's codebase are a layer-violation bug.
- **Read-only.** No order routing, no broker integration, no
  auto-trading in v1 or v2. The operator is always the decision-maker.
- **Patient by design.** Strigoi look for rare, high-quality setups.
  Empty nights are the norm, not a bug.
- **Backtests with discipline.** Every Strigoi has a backtest gate
  before it runs in production: look-ahead bias avoided, survivorship
  bias acknowledged, results documented.

---

## Disclaimer

Dracul is a personal research tool. It does not constitute financial,
investment, legal, or tax advice. Past performance of any anomaly is
not indicative of future results. The operator is solely responsible
for any decision made on the basis of Dracul's output.

---

## License

Sustainable Use License — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
