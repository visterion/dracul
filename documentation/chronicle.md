# Chronicle (frontend)

The Chronicle is Dracul's Vue 3 / Vuetify 3 frontend. It is the
operator's morning view: what did the Strigoi find overnight, and
what's worth a closer look.

## Stack

- Vue 3 (Composition API)
- Vuetify 3
- Pinia
- Vite

## Planned views

| Route | Purpose |
|---|---|
| `/prey` | Filterable list of recent findings (by Strigoi, anomaly, score) |
| `/prey/:id` | Single-prey detail: thesis, signals, risks, source filings |
| `/verdicts` | Consolidated verdicts (multi-Strigoi consensus) |
| `/cost` | Cost dashboard, fed from Vistierie's cost ledger |

TODO: fill in once `chronicle/` is scaffolded.
