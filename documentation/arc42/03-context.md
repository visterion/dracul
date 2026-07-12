# 3. Context and Scope

> [!NOTE]
> **AI-Generated: this section** — inferred from code and `documentation/`, needs human review.

## System boundary

Dracul is a **Vistierie consumer**. It owns investment-domain logic; Vistierie
owns the generic agent runtime. The two-consumer rule (HiveMem + Dracul) keeps
Vistierie's API honest.

| Vistierie owns | Dracul owns |
|---|---|
| Bee/Strigoi runtime, schedule, recursion, context shielding | Strigoi domain logic and prompts |
| Provider plugins, tier routing, cost ledger, kill switch | Market-data adapters (EDGAR, prices, news, calendar) |
| Run history, audit, batch API | Pre-screen logic (deterministic filters before the LLM) |
| Webhook completion delivery | `Prey` / `Verdict` domain, persistence, frontend, backtest |

## External interfaces

- **Vistierie** — LLM gateway + agent runtime, reached over an in-cluster URL.
  See `documentation/vistierie-integration.md`.
- **Market data (hunting grounds)** — EDGAR filings, price series, news, market
  calendar. See `documentation/hunting-grounds.md`.
- **Chronicle UI** — Vue 3 frontend served by the backend; REST API documented in
  `documentation/api.md`.
- **Cloudflare Access** — fronts operator endpoints in production.
