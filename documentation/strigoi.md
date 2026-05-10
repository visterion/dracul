# Strigoi

A Strigoi is a specialised hunter agent. Each Strigoi targets exactly
one documented market anomaly. Strigoi are registered with Vistierie
as agents; Vistierie owns the runtime, Dracul owns the domain logic.

## Roster (planned)

| # | Name | Anomaly | Tier | Source |
|---|------|---------|------|--------|
| 1 | strigoi-spin | Spin-offs, forced selling | reasoning | Greenblatt 1997 |
| 2 | strigoi-insider | Insider cluster buys | routine | Lakonishok & Lee 2001 |
| 3 | strigoi-echo | Post-earnings drift | routine | Bernard & Thomas 1989 |
| 4 | strigoi-lazarus | Quality at 52w low | reasoning | Piotroski 2000 |
| 5 | strigoi-index | Index-inclusion drift | routine | S&P / Russell studies |
| 6 | strigoi-merger | M&A arbitrage | reasoning | Mitchell & Pulvino 2001 |

## Pattern

Every Strigoi follows the same shape:

1. **Pre-screen** (deterministic, no LLM) -- pulls candidates from a
   hunting-ground adapter (EDGAR, prices, news, calendar) and filters
   to the ones worth spending tokens on.
2. **LLM evaluation** via Vistierie -- a Sonnet-tier or Haiku-tier
   call that returns a structured `Prey` JSON.
3. **Persist** to `dracul.prey`. The Strigoi's tool implementations
   live in Dracul; Vistierie just dispatches against the registered
   webhook URLs.

TODO: fill in once the first Strigoi (Strigoi-Spin) is implemented.
