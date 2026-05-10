# Vistierie integration

Dracul is a Vistierie consumer. This page documents the boundary.

## Tenant

Dracul registers itself as the `dracul` tenant inside Vistierie.
Routing rules map `<dracul, *, reasoning>` and `<dracul, *, routine>`
to concrete provider+model combinations; flipping a Strigoi from
Sonnet to Haiku is a routing-rule edit, not a code change.

## Tier conventions

| Tier | Use for | Example Strigoi |
|---|---|---|
| `reasoning` | Dense filings, multi-factor judgement | strigoi-spin, strigoi-lazarus, strigoi-merger |
| `routine` | Pattern-matching, simple classification | strigoi-insider, strigoi-echo, strigoi-index |

## Tool webhooks

Each Strigoi declares its tools (`prey.scan`, `filing.fetch`, etc.) as
webhook URLs pointing back into `dracul-app`. Vistierie calls those
webhooks from inside the tool-dispatch loop. The webhook token is
shared via the `DRACUL_TOOL_WEBHOOK_TOKEN` env var on the Dracul side.

## Completion webhook

Vistierie POSTs the validated agent output to a single Dracul webhook
when a run finishes. Dracul writes the result into `dracul.prey`.

## What Vistierie owns vs Dracul owns

See the table in `CLAUDE.md` -- non-negotiable.
