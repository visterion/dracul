# 1. Introduction and Goals

> [!NOTE]
> **AI-Generated: this section** — inferred from code and `documentation/`, needs human review.

## What Dracul is

Dracul is an autonomous, **read-only investment-research system**. It hunts
documented market anomalies (spin-offs, insider clusters, PEAD, index-inclusion
drift, M&A arbitrage, quality-at-52-week-low) and surfaces candidates for a human
to review. It is **not** an auto-trader and **not** investment advice.

The metaphor: Dracul is the lord; his **Strigoi** are specialised hunter agents
that wake at night, scan the market for one specific pattern, and surface
candidates. The operator reviews findings each morning and decides whether to act.

## Quality goals

| Goal | Motivation |
|---|---|
| Read-only safety | Dracul never places trades; it researches only. |
| Layer discipline | Generic agent-runtime concerns stay in Vistierie; investment logic stays in Dracul (see [ADR](./adr/)). |
| Cost control | LLM work runs through Vistierie's tier routing, cost ledger, and per-agent budgets. |
| Reproducible research | Deterministic pre-screens run before any LLM call; findings are persisted with evidence. |

## Stakeholders

- **Operator / owner** — reviews Strigoi findings, makes all trade decisions.
- **Contributors** — extend Strigoi patterns and market-data adapters.
