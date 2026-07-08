# Dracul
<img width="1456" height="720" alt="image" src="https://github.com/user-attachments/assets/7630ef1d-3d4b-4402-8420-268c4247ddb9" />

> **An autonomous investment-research system. Six specialised hunters scan the market every night for documented anomalies; a weekly reviewer learns from their track record; a daytime guardian watches active positions.**

Dracul runs a roster of **Strigoi** — agentic hunters, each tuned to one
academically-documented anomaly (spin-offs, insider clusters,
post-earnings drift, quality-at-52w-low, index-inclusion drift, M&A
arbitrage). They sleep through the trading day, wake at night, scan
their hunting grounds, and lay out what they found in the Chronicle by
morning. The **Voievod** reviews outcomes weekly and proposes heuristics
that make future hunts smarter. The **Daywalker** guards active positions
during market hours and fires alerts when something demands attention.
The operator decides what, if anything, to do with the findings.

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

## The Tale

> *The night belongs to the patient.*

When the closing bell falls silent and Wall Street goes to sleep, something else stirs. Deep in the crypt, Dracul rises from his stone sarcophagus, brushes the dust of the day from his cloak, and opens the great book in which the anomalies of the world are recorded. He strikes the iron knocker three times against the oaken door -- and his Strigoi awaken.

Six shadows step into the moonlight. His brood. His specialists. Each one hungers for a different kind of blood.

**Strigoi-Spin** hunts the newborns -- fresh spin-offs, freshly cut from their parents, bleeding institutional sellers who cannot hold them.

**Strigoi-Insider** follows the trail of those who know more -- clusters of executives buying their own stock with their own money.

**Strigoi-Echo** waits patiently for earnings reports and listens to how slowly the mortals process surprise.

**Strigoi-Lazarus** digs in the graveyards of fifty-two-week lows, looking for skeletons whose bones are still sound.

**Strigoi-Index** stalks the rebalancing calendars, where billions of dollars must buy at any price.

**Strigoi-Merger** stands between announced deals and their closing prices, drinking from the spread.

They scatter into the night. Each to his own hunting ground. Each with the patience of the immortal.

By dawn, they return. Each carries prey -- not bodies, but knowledge. Lists of instruments where blood will flow. Theses for why the bleeding will continue. Risks they observed along the way.

The **Voievod** learns. Weekly, he reviews what the Strigoi found and what actually happened. He proposes heuristics. The ones that hold become part of the next hunt.

The **Daywalker** never sleeps during market hours. While the Strigoi rest in their coffins, he watches the positions you hold, monitors the news, and speaks when something demands your attention.

Dracul reads their reports. When three Strigoi independently bring back the same name -- Sandisk, perhaps, on a February morning -- he knows: this is more than coincidence. This is a setup. He inscribes the **Verdict** in his chronicle. He does not decide whether you should hunt. He presents the case -- curated, reasoned, with risks. You, the mortal who summoned him, decide. That is the bargain.

> *The immortal know no impatience.*

-- [Read the full tale](documentation/the-hunt.md)

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
                │  agent runtime: scheduling, routing, │
                │  cost ledger, kill switch, audit     │
                └──────────────┬───────────────────────┘
                               │ register agents,
                               │ tool webhooks,
                               │ tier-based routing
                               ▼
┌───────────────────────────────────────────────────────────────┐
│                            Dracul                              │
│                                                               │
│  ┌──────────────────────── nightly ──────────────────────┐   │
│  │  Strigoi-Spin · Strigoi-Insider · Strigoi-Echo        │   │
│  │  Strigoi-Lazarus · Strigoi-Index · Strigoi-Merger     │   │
│  └──────────────────────────┬────────────────────────────┘   │
│                             │ Prey                            │
│  ┌────── weekly ──────┐     │     ┌──── market hours ──────┐  │
│  │     Voievod        │     │     │      Daywalker         │  │
│  │ reviews outcomes,  │     │     │  watches watchlist,    │  │
│  │ proposes patterns  │     │     │  fires alerts          │  │
│  └────────┬───────────┘     │     └──────────┬─────────────┘  │
│           │ Patterns        │               │ Alerts          │
│           ▼                 ▼               ▼                 │
│  ┌────────────────────────────────────────────────────┐       │
│  │      Hunting grounds → Agora (MCP)                 │       │
│  │   prices · filings · news · earnings calendar      │       │
│  └────────────────────────────────────────────────────┘       │
│                             │                                  │
│                             ▼                                  │
│                   ┌──────────────────┐                         │
│                   │   Synthesizer    │ consensus → Verdict     │
│                   └────────┬─────────┘                         │
│                            ▼                                   │
│                   ┌──────────────────┐                         │
│                   │  Crypt (PG)      │ prey · verdicts ·       │
│                   │                  │ patterns · alerts ·     │
│                   │                  │ watchlist               │
│                   └────────┬─────────┘                         │
│                            ▼                                   │
│                   ┌──────────────────┐                         │
│                   │    Chronicle     │  Vue 3 / Vuetify 3      │
│                   │    (8 views)     │  SSE live updates       │
│                   └──────────────────┘                         │
└───────────────────────────────────────────────────────────────┘
```

Vistierie owns the agent runtime (scheduling, recursion, context
shielding, cost ledger, kill switch, tier-based routing). Dracul owns
the investment domain (Strigoi logic, Voievod, Daywalker, market-data
and filing access via Agora (MCP), pre-screen,
prey/verdict/pattern/alert persistence, Chronicle frontend). The split
is non-negotiable — see `CLAUDE.md`.

---

## The residents of the crypt

### The six Strigoi (nightly hunters)

| # | Strigoi | Anomaly | Tier | Source |
|---|---------|---------|------|--------|
| 1 | **Strigoi-Spin** | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | **Strigoi-Insider** | Insider cluster buys | routine | Lakonishok & Lee 2001 |
| 3 | **Strigoi-Echo** | Post-earnings drift (PEAD) | routine | Bernard & Thomas 1989 |
| 4 | **Strigoi-Lazarus** | Quality at 52-week low | reasoning | Piotroski 2000 |
| 5 | **Strigoi-Index** | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | **Strigoi-Merger** | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |

Each Strigoi follows the same pattern: deterministic pre-screen against
its hunting ground → LLM evaluation via Vistierie at the appropriate
tier → structured `Prey` JSON written to the Crypt. Switching tiers is
a Vistierie routing-rule edit, never a code change.

When two or more Strigoi flag the same instrument independently, the
Synthesizer produces a **Verdict** — a consolidated summary of why the
consensus formed and what each perspective saw.

### Voievod (weekly reviewer)

Runs every Sunday. Reviews all Prey whose time-horizon has elapsed,
compares thesis against actual price return, and proposes learned
**Patterns** to the Pattern Library. Patterns only activate when the
operator approves them — they then become prompt context for the next
Strigoi run, closing the feedback loop.

### Daywalker (streaming guardian)

Active during US market hours. Polls prices every 5 minutes for all
watchlist items. Reacts to news, Form-4 filings, and price spikes.
Uses a cheap Haiku pre-filter before escalating to a full Sonnet
assessment. Critical alerts go to Telegram immediately.

### Executor (guarded paper trading, opt-in)

Not a hunter and disabled by default. Consumes signals from the Strigoi,
gropar, or the operator as advice, then decides whether to act — every
veto and the final order guard are enforced in code, never by the LLM.
Trades **paper only**, on a non-live connection that makes live trading
physically unreachable. **Paper simulation, not investment advice, not a
live-trading system.**

---

## Quick start

> Requires a running [Vistierie](https://github.com/visterion/vistierie)
> instance and a Postgres database.

```bash
docker run --rm -p 8080:8080 \
  -e DRACUL_DB_URL=jdbc:postgresql://host.docker.internal:5432/dracul \
  -e DRACUL_DB_USER=dracul \
  -e DRACUL_DB_PASSWORD=dracul \
  -e DRACUL_VISTIERIE_BASE_URL=http://vistierie:8090 \
  -e DRACUL_VISTIERIE_TOKEN='<bearer-token-issued-by-vistierie>' \
  -e DRACUL_TOOL_WEBHOOK_TOKEN='<dracul-side-secret>' \
  -e DRACUL_AGORA_BASE_URL='http://agora:8080' \
  -e DRACUL_AGORA_TOKEN='<bearer-token-to-reach-agora>' \
  -e TELEGRAM_BOT_TOKEN='<optional>' \
  -e TELEGRAM_CHAT_ID='<optional>' \
  ghcr.io/visterion/dracul:main
```

Full setup, tenant bootstrap, and operations runbook:
[`documentation/operations.md`](documentation/operations.md).

---

## Documentation

| | |
|---|---|
| [architecture.md](documentation/architecture.md) | System overview, all modules, domain model, data flow |
| [strigoi.md](documentation/strigoi.md) | Strigoi roster, Voievod, Daywalker — the hunt pattern and tier routing |
| [hunting-grounds.md](documentation/hunting-grounds.md) | Market-data & filings via Agora (MCP) |
| [vistierie-integration.md](documentation/vistierie-integration.md) | Vistierie ownership boundary, tier conventions, webhooks |
| [api.md](documentation/api.md) | REST endpoint reference, SSE live-update stream |
| [configuration.md](documentation/configuration.md) | All `dracul.*` properties and env vars |
| [chronicle.md](documentation/chronicle.md) | Vue 3 frontend — 8 views, design system, live updates |
| [operations.md](documentation/operations.md) | Tenant setup, kill switch, Daywalker hours, backups |
| [the-hunt.md](documentation/the-hunt.md) | The full tale of Dracul and his Strigoi |

---

## Build from source

Requires JDK 25, Node 22+, and Docker (for the Postgres testcontainer
used in tests).

```bash
export JAVA_HOME=/usr/local/lib/jdk-25.0.2+10
export PATH=$JAVA_HOME/bin:$PATH
cd java-server
./mvnw test                        # full suite
./mvnw -DskipTests package
java -jar dracul-app/target/dracul-app-*.jar
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
- **Read-only by default, with one narrow, guarded exception.** The six
  Strigoi, Voievod, gropar, and Daywalker never route orders or touch a
  broker. The opt-in Executor agent (off by default) is the deliberate
  exception: **guarded paper trading only** — every entry is re-checked in
  code before it reaches the broker, and the connection is a non-live paper
  connection by construction. No agent ever trades live capital. The
  operator is always the decision-maker.
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
