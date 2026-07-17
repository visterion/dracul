package de.visterion.dracul.renfield;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

@Repository
public class TradeProposalRepository {

    private final JdbcClient jdbc;

    public TradeProposalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one proposal row; returns rows inserted — 0 when the (run_id, symbol)
     *  unique key already exists (idempotent webhook retry), 1 otherwise. */
    public int insert(String owner, String symbol, String action, String entryZone, String stop,
            BigDecimal confidence, String rationale, String marketNote, String runId) {
        return jdbc.sql("""
                INSERT INTO trade_proposals
                  (id, owner, symbol, action, entry_zone, stop, confidence, rationale,
                   market_note, run_id)
                VALUES (:id, :o, :s, :a, :ez, :st, :c, :r, :mn, :run)
                ON CONFLICT (run_id, symbol) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("o", owner)
                .param("s", symbol)
                .param("a", action)
                .param("ez", entryZone)
                .param("st", stop)
                .param("c", confidence)
                .param("r", rationale)
                .param("mn", marketNote)
                .param("run", runId)
                .update();
    }
}
