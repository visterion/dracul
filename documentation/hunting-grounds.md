# Hunting grounds

Market-data adapters live in `dracul-hunting-grounds/`. Each adapter
isolates one external API behind a domain-shaped interface so Strigoi
never see vendor-specific schemas.

## Planned adapters

| Adapter | Source | Purpose |
|---|---|---|
| `edgar-adapter` | SEC EDGAR | Form-10 / 10-12B / Form-4 / 8-K filings |
| `prices-adapter` | Yahoo / Alpha Vantage / Polygon | Daily OHLCV, splits, dividends |
| `news-adapter` | Finnhub / NewsAPI | Material-news firehose |
| `calendar-adapter` | Multiple | Earnings, index reconstitutions |

## Conventions

- Rate-limiting and retry policy live in the adapter, not in Strigoi.
- Adapter responses are normalised to Dracul domain types before
  leaving the adapter module.
- Free-tier limits are documented per adapter; paid upgrades are
  configurable via `application.yml`.

TODO: fill in per-adapter pages as they ship.
