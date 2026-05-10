# Configuration

> Stub. Filled in as `application.yml` properties land.

## Environment variables (planned)

| Variable | Purpose |
|---|---|
| `DRACUL_DB_URL` | Postgres JDBC URL (`dracul` schema) |
| `DRACUL_DB_USER` / `DRACUL_DB_PASSWORD` | Postgres credentials |
| `DRACUL_VISTIERIE_BASE_URL` | Where to reach Vistierie (e.g. `http://vistierie:8090`) |
| `DRACUL_VISTIERIE_TOKEN` | Bearer token Dracul uses against Vistierie |
| `DRACUL_TOOL_WEBHOOK_TOKEN` | Token Vistierie uses to call back into Dracul |
| `EDGAR_USER_AGENT` | Required by SEC EDGAR (`Name email@example.com`) |
| `ALPHAVANTAGE_API_KEY` | Optional, prices-adapter |
| `FINNHUB_API_KEY` | Optional, news-adapter |
