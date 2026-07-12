# ADR-0001: Dracul is a Vistierie consumer, not a runtime owner

- **Status:** Accepted
- **Date:** 2026-07-12 (documenting a long-standing decision)

## Context

Dracul needs an LLM-driven agent runtime (scheduling, recursion, provider
routing, cost control). That machinery is generic and is also needed by HiveMem.
Duplicating it inside Dracul would fork behaviour and split maintenance.

## Decision

Keep all generic agent-runtime concerns in **Vistierie** and consume them from
Dracul over an in-cluster URL. Dracul owns only investment-specific logic:
Strigoi patterns, prompts, market-data adapters, pre-screens, and the
`Prey`/`Verdict` domain. Anything generic about agent runtime goes upstream into
Vistierie; investment terms appearing inside Vistierie are a layer-violation bug.

## Consequences

- Vistierie stays reusable; the two-consumer rule (HiveMem + Dracul) validates its API.
- A deploy that changes an already-registered agent's schema/prompt needs an extra
  reset + budget step (see the private `docs/operations-runbook.md`).
- Dracul carries no runtime scheduling/cost code of its own to maintain.
