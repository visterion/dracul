package de.visterion.dracul.renfield;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class TradeProposalRepository {

    private static final Logger log = LoggerFactory.getLogger(TradeProposalRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public TradeProposalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts one proposal row; returns rows inserted — 0 when the (run_id, symbol)
     *  unique key already exists (idempotent webhook retry), 1 otherwise.
     *  {@code newsSentimentJson} is an already-serialized JSON string (array of
     *  {headline, sentiment} objects) or null; it is bound as text and cast to jsonb —
     *  binding a JsonNode directly fails at runtime (house convention, see
     *  ExecutorSignalRepository#insert / WatchlistRepository#insert). */
    public int insert(String owner, String symbol, String action, String entryZone, String stop,
            BigDecimal confidence, String rationale, String marketNote, String runId, String newsSentimentJson) {
        return jdbc.sql("""
                INSERT INTO trade_proposals
                  (id, owner, symbol, action, entry_zone, stop, confidence, rationale,
                   market_note, run_id, news_sentiment)
                VALUES (:id, :o, :s, :a, :ez, :st, :c, :r, :mn, :run, CAST(:ns AS jsonb))
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
                .param("ns", newsSentimentJson)
                .update();
    }

    /** Proposals for one owner from the last {@code days} days, newest first; within
     *  a run (same created_at down to the transaction's clock granularity) rows keep
     *  insertion order via the ctid tie-break — same convention as WatchlistRepository's
     *  alert ordering. */
    public List<TradeProposal> findRecent(String owner, int days) {
        return jdbc.sql("""
                SELECT id, symbol, action, entry_zone, stop, confidence, rationale,
                       market_note, run_id, created_at, news_sentiment
                FROM trade_proposals
                WHERE owner = :owner
                  AND created_at > now() - (:days || ' days')::interval
                ORDER BY created_at DESC, ctid ASC
                """)
                .param("owner", owner)
                .param("days", days)
                .query(this::mapRow)
                .list();
    }

    /** Up to 5 most-recent prior proposals per symbol within the last 10 days, keyed by
     *  symbol. Uses a row_number() window (not a global LIMIT 5, which would starve
     *  symbols with few proposals in favor of one noisy symbol) so every requested
     *  symbol gets its own top-5. Empty/null input never reaches SQL — {@code IN ()}
     *  is invalid — and returns an empty map instead. */
    public Map<String, List<PriorProposal>> findPriorBySymbols(String owner, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();

        Map<String, List<PriorProposal>> result = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT symbol, created_at, action, confidence FROM (
                  SELECT symbol, created_at, action, confidence,
                         row_number() OVER (PARTITION BY symbol ORDER BY created_at DESC) AS rn
                    FROM trade_proposals
                   WHERE owner = :owner AND symbol IN (:symbols)
                     AND created_at > now() - interval '10 days'
                ) t
                WHERE rn <= 5
                ORDER BY symbol, created_at DESC
                """)
                .param("owner", owner)
                .param("symbols", symbols)
                .query((rs, n) -> {
                    String symbol = rs.getString("symbol");
                    result.computeIfAbsent(symbol, k -> new java.util.ArrayList<>())
                            .add(new PriorProposal(
                                    rs.getTimestamp("created_at").toInstant().toString(),
                                    rs.getString("action"),
                                    rs.getBigDecimal("confidence")));
                    return null;
                })
                .list();
        return result;
    }

    private TradeProposal mapRow(ResultSet rs, int n) throws SQLException {
        return new TradeProposal(
                rs.getString("id"),
                rs.getString("symbol"),
                rs.getString("action"),
                rs.getString("entry_zone"),
                rs.getString("stop"),
                rs.getBigDecimal("confidence"),
                rs.getString("rationale"),
                rs.getString("market_note"),
                rs.getString("run_id"),
                rs.getTimestamp("created_at").toInstant(),
                readJson(rs.getString("news_sentiment")));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            log.error("Failed to deserialize trade_proposals.news_sentiment: {}", json, e);
            return null;
        }
    }
}
