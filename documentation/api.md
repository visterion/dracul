# REST API

> Stub. Endpoints are defined as the `dracul-app` module is built out.

The Chronicle frontend is the primary consumer of this API.

## Planned endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/prey` | List recent prey, filterable by Strigoi / anomaly / date |
| GET | `/prey/{id}` | Single prey detail (signals, risks, thesis) |
| GET | `/verdicts` | Consolidated verdicts (multi-Strigoi consensus) |
| GET | `/strigoi` | Strigoi roster + last-run status (proxied from Vistierie) |
| POST | `/strigoi/{name}/hunt` | Manual one-off hunt (proxied to Vistierie) |
| GET | `/cost` | Cost-rollup view (proxied from Vistierie) |
| POST | `/admin/kill` | Tenant kill switch (proxied to Vistierie) |
