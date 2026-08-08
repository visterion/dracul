-- Renfield-Reparatur (Spec 2026-08-08): Sentiment wird nicht mehr verworfen, die
-- prior_proposals-Abfrage bekommt ihren Index, und der Bestand zum Zeitpunkt des
-- Triggers wird festgehalten, damit die Aktions-Prüfung nicht gegen einen späteren
-- (womöglich ausgefallenen) Depot-Zustand urteilt.
ALTER TABLE trade_proposals ADD COLUMN news_sentiment jsonb NULL;

CREATE INDEX trade_proposals_owner_symbol_created_idx
    ON trade_proposals (owner, symbol, created_at DESC);

CREATE TABLE renfield_run_context (
    run_id          VARCHAR     NOT NULL,
    symbol          VARCHAR     NOT NULL,
    held            BOOLEAN     NOT NULL,
    position_source VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, symbol)
);
