# Operations

> Stub.

## Deployment

- Image: `ghcr.io/visterion/dracul:main`
- Topology: same Docker network as Vistierie. No TLS / mTLS in v1.
- Postgres: shares the host with Vistierie's Postgres; Dracul uses
  its own database/schema (`dracul`).

## Backups

TODO: nightly `pg_dump` of the `dracul` database, retained alongside
HiveMem / Vistierie backups.

## Kill switch

Dracul has no kill switch of its own. To stop all Strigoi activity,
flip the kill switch on the `dracul` tenant in Vistierie. The next
cron tick is suppressed until the switch is released.
