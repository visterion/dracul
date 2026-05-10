# Architecture

> Stub. Fill in when the first slice ships.

## Overview

Dracul is a Spring Boot 4 service plus a Vue 3 / Vuetify 3 frontend
(`chronicle`). It consumes [Vistierie](https://github.com/visterion/vistierie)
as its agent runtime: Strigoi are registered as Vistierie agents,
Vistierie owns scheduling, tier-based model routing, cost tracking, and
the kill switch.

## Modules (planned)

```
dracul/
├── dracul-domain/              # Pure domain: Instrument, Prey, Verdict, AnomalyType
├── dracul-strigoi/             # Strigoi implementations (one module per hunter)
├── dracul-hunting-grounds/     # Market-data adapters (EDGAR, prices, news, calendar)
├── dracul-crypt/               # Postgres persistence (append-only, Flyway)
├── dracul-app/                 # Spring Boot wiring, REST API, Vistierie client
└── chronicle/                  # Vue 3 frontend
```

## Data model

TODO: document the `dracul.*` schema once the first migration lands.
Append-only style (à la HiveMem): each Strigoi run produces immutable
`prey` rows; `verdict` rows reference the prey rows that were
consolidated.
