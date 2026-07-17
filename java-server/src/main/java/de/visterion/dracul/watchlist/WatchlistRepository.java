package de.visterion.dracul.watchlist;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class WatchlistRepository {

    private static final Logger log = LoggerFactory.getLogger(WatchlistRepository.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public WatchlistRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<WatchlistItem> findAll() {
        var items = jdbc.sql("""
                SELECT id, ticker, company_name, current_price, day_change_percent,
                       status, added_at, tag, verdict_id, price_history_30d,
                       entry_price, share_count, user_id, currency, entry_currency
                FROM watchlist_items
                ORDER BY added_at DESC
                """)
                .query(itemRowMapper())
                .list();

        if (items.isEmpty()) return items;

        var alertsByItem = new HashMap<String, List<WatchlistAlert>>();
        jdbc.sql("""
                SELECT id, watchlist_item_id, at, message, level, severity
                FROM daywalker_alerts
                ORDER BY watchlist_item_id, ctid
                """)
                .query((rs, rowNum) -> {
                    var itemId = rs.getString("watchlist_item_id");
                    alertsByItem.computeIfAbsent(itemId, k -> new ArrayList<>())
                            .add(new WatchlistAlert(
                                    rs.getString("id"),
                                    rs.getString("at"),
                                    rs.getString("message"),
                                    rs.getString("level"),
                                    rs.getString("severity")
                            ));
                    return null;
                })
                .list();

        return attachAlerts(items, alertsByItem);
    }

    public List<WatchlistItem> findAllByUser(String userId) {
        var items = jdbc.sql("""
                SELECT id, ticker, company_name, current_price, day_change_percent,
                       status, added_at, tag, verdict_id, price_history_30d,
                       entry_price, share_count, user_id, currency, entry_currency
                FROM watchlist_items
                WHERE user_id = :userId
                ORDER BY added_at DESC
                """)
                .param("userId", userId)
                .query(itemRowMapper())
                .list();

        if (items.isEmpty()) return items;

        var alertsByItem = new HashMap<String, List<WatchlistAlert>>();
        jdbc.sql("""
                SELECT id, watchlist_item_id, at, message, level, severity
                FROM daywalker_alerts
                WHERE user_id = :userId
                ORDER BY watchlist_item_id, ctid
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    var itemId = rs.getString("watchlist_item_id");
                    alertsByItem.computeIfAbsent(itemId, k -> new ArrayList<>())
                            .add(new WatchlistAlert(
                                    rs.getString("id"),
                                    rs.getString("at"),
                                    rs.getString("message"),
                                    rs.getString("level"),
                                    rs.getString("severity")
                            ));
                    return null;
                })
                .list();

        return attachAlerts(items, alertsByItem);
    }

    private org.springframework.jdbc.core.RowMapper<WatchlistItem> itemRowMapper() {
        return (rs, rowNum) -> {
            var ep = rs.getBigDecimal("entry_price");
            var sc = rs.getBigDecimal("share_count");
            return new WatchlistItem(
                    rs.getString("id"),
                    rs.getString("ticker"),
                    rs.getString("company_name"),
                    rs.getDouble("current_price"),
                    rs.getDouble("day_change_percent"),
                    rs.getString("status"),
                    rs.getString("added_at"),
                    rs.getString("tag"),
                    rs.getString("verdict_id"),
                    new ArrayList<>(),
                    readDoubleList(rs.getString("price_history_30d")),
                    ep == null ? null : ep.doubleValue(),
                    sc == null ? null : sc.doubleValue(),
                    rs.getString("user_id"),
                    rs.getString("currency"),
                    rs.getString("entry_currency")
            );
        };
    }

    private List<WatchlistItem> attachAlerts(List<WatchlistItem> items,
                                              Map<String, List<WatchlistAlert>> alertsByItem) {
        return items.stream()
                .map(item -> new WatchlistItem(
                        item.id(), item.ticker(), item.companyName(),
                        item.currentPrice(), item.dayChangePercent(),
                        item.status(), item.addedAt(), item.tag(),
                        item.verdictId(),
                        alertsByItem.getOrDefault(item.id(), List.of()),
                        item.priceHistory30d(),
                        item.entryPrice(), item.shareCount(),
                        item.owner(),
                        item.currency(), item.entryCurrency()
                ))
                .toList();
    }

    public Optional<WatchlistItem> findByUserAndTicker(String userId, String ticker) {
        return findAllByUser(userId).stream()
                .filter(i -> i.ticker().equals(ticker))
                .findFirst();
    }

    /** Count of fully-held positions (tag HELD with both entry price and share count). */
    public long countHeldByUser(String userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM watchlist_items
                WHERE user_id = :userId
                  AND tag = 'HELD'
                  AND entry_price IS NOT NULL
                  AND share_count IS NOT NULL
                """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Count of fully-held positions across all users (tag HELD with both entry price and share count). */
    public long countHeldAll() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM watchlist_items
                WHERE tag = 'HELD'
                  AND entry_price IS NOT NULL
                  AND share_count IS NOT NULL
                """)
                .query(Long.class)
                .single();
    }

    public Optional<WatchlistItem> findById(String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return Optional.empty(); }

        var items = jdbc.sql("""
                SELECT id, ticker, company_name, current_price, day_change_percent,
                       status, added_at, tag, verdict_id, price_history_30d,
                       entry_price, share_count, user_id, currency, entry_currency
                FROM watchlist_items
                WHERE id = :id
                """)
                .param("id", uuid)
                .query(itemRowMapper())
                .list();

        if (items.isEmpty()) return Optional.empty();

        var alertsByItem = new HashMap<String, List<WatchlistAlert>>();
        jdbc.sql("""
                SELECT id, watchlist_item_id, at, message, level, severity
                FROM daywalker_alerts
                WHERE watchlist_item_id = :id
                ORDER BY ctid
                """)
                .param("id", uuid)
                .query((rs, rowNum) -> {
                    var itemId = rs.getString("watchlist_item_id");
                    alertsByItem.computeIfAbsent(itemId, k -> new ArrayList<>())
                            .add(new WatchlistAlert(
                                    rs.getString("id"),
                                    rs.getString("at"),
                                    rs.getString("message"),
                                    rs.getString("level"),
                                    rs.getString("severity")
                            ));
                    return null;
                })
                .list();

        return attachAlerts(items, alertsByItem).stream().findFirst();
    }

    public WatchlistItem insert(String userId, String ticker, String companyName,
                                double currentPrice, java.util.List<Double> history,
                                String tag, String sourceVerdictId, String currency) {
        UUID id = UUID.randomUUID();
        UUID verdictUuid = sourceVerdictId == null ? null : UUID.fromString(sourceVerdictId);
        String historyJson;
        try { historyJson = mapper.writeValueAsString(history); }
        catch (Exception e) { historyJson = "[]"; }

        jdbc.sql("""
                INSERT INTO watchlist_items
                  (id, ticker, company_name, current_price, day_change_percent,
                   status, added_at, tag, verdict_id, price_history_30d, user_id, currency)
                VALUES
                  (:id, :ticker, :name, :price, 0,
                   'calm', CURRENT_DATE, :tag, :vid, CAST(:hist AS jsonb), :userId, :currency)
                """)
                .param("id", id).param("ticker", ticker).param("name", companyName)
                .param("price", currentPrice).param("tag", tag == null ? "" : tag)
                .param("vid", verdictUuid).param("hist", historyJson)
                .param("userId", userId).param("currency", currency)
                .update();

        return findById(id.toString()).orElseThrow();
    }

    public WatchlistItem mergeVerdictIdIfNull(String id, String sourceVerdictId) {
        if (sourceVerdictId != null) {
            UUID uuid = UUID.fromString(id);
            UUID vid = UUID.fromString(sourceVerdictId);
            jdbc.sql("""
                    UPDATE watchlist_items
                       SET verdict_id = :vid
                     WHERE id = :id AND verdict_id IS NULL
                    """)
                    .param("vid", vid).param("id", uuid)
                    .update();
        }
        return findById(id).orElseThrow();
    }

    public boolean updateTag(String id, String tag) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        int rows = jdbc.sql("UPDATE watchlist_items SET tag = :tag WHERE id = :id")
                .param("tag", tag).param("id", uuid).update();
        return rows > 0;
    }

    public boolean updatePosition(String id, Double entryPrice, Double shareCount,
                                   String entryCurrency) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        int rows = jdbc.sql("""
                UPDATE watchlist_items
                   SET entry_price = :entryPrice, share_count = :shareCount,
                       entry_currency = :entryCurrency
                 WHERE id = :id
                """)
                .param("entryPrice", entryPrice)
                .param("shareCount", shareCount)
                .param("entryCurrency", entryCurrency)
                .param("id", uuid)
                .update();
        return rows > 0;
    }

    /** One sweep representative per distinct ticker across all users — ticker, company name,
     *  and the last-refreshed price (the Daywalker detectors embed currentPrice into emitted
     *  events; without it the alert/LLM context would show 0). */
    public record SweepRow(String ticker, String companyName, double currentPrice) {}

    /** Distinct (ticker, company_name, current_price) across all users for the Daywalker sweep. */
    public List<SweepRow> distinctSweepRows() {
        return jdbc.sql("""
                SELECT DISTINCT ON (ticker) ticker, company_name, current_price
                FROM watchlist_items
                ORDER BY ticker, added_at DESC
                """)
                .query((rs, rowNum) -> new SweepRow(
                        rs.getString("ticker"),
                        rs.getString("company_name"),
                        rs.getDouble("current_price")))
                .list();
    }

    /** Distinct tickers across all users — input to the background price refresher. */
    public List<String> distinctTickers() {
        return jdbc.sql("SELECT DISTINCT ticker FROM watchlist_items")
                .query(String.class)
                .list();
    }

    public java.util.List<String> distinctCurrencies() {
        return jdbc.sql("SELECT DISTINCT currency FROM watchlist_items WHERE currency IS NOT NULL")
                .query(String.class)
                .list();
    }

    /** Update price + day-change for every row of a ticker; returns rows affected. */
    public int updatePriceByTicker(String ticker, double price, double dayChangePercent) {
        return jdbc.sql("""
                UPDATE watchlist_items
                   SET current_price = :price, day_change_percent = :dcp
                 WHERE ticker = :ticker
                """)
                .param("price", price)
                .param("dcp", dayChangePercent)
                .param("ticker", ticker)
                .update();
    }

    public boolean deleteById(String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        jdbc.sql("DELETE FROM daywalker_alerts WHERE watchlist_item_id = :id")
                .param("id", uuid).update();
        int rows = jdbc.sql("DELETE FROM watchlist_items WHERE id = :id")
                .param("id", uuid).update();
        return rows > 0;
    }

    /** Risk fields (entry_date as ISO string, initial_stop, and the morning-report snapshot)
     *  keyed by item id — for gropar (intentionally across all owners: gropar is a system
     *  agent that routes signals to owners by position id, mirroring fetchHeldPositions'
     *  findAll() usage). */
    public Map<String, PositionRisk> positionRiskByItemId() {
        return jdbc.sql("""
                SELECT id, entry_date, initial_stop,
                       active_stop, next_target_2r, current_close, atr
                  FROM watchlist_items
                """)
                .query((rs, rowNum) -> new PositionRisk(
                        rs.getString("id"),
                        rs.getString("entry_date"),
                        rs.getBigDecimal("initial_stop"),
                        rs.getBigDecimal("active_stop"),
                        rs.getBigDecimal("next_target_2r"),
                        rs.getBigDecimal("current_close"),
                        rs.getBigDecimal("atr")))
                .list()
                .stream()
                .collect(Collectors.toMap(PositionRisk::id, r -> r));
    }

    /** Overwrites the per-position risk snapshot every gropar run (the trailing
     *  stop moves — NOT freeze-once). Any field may be null when unavailable.
     *  snapshotAt is stamped by the caller. Returns true if a row was updated. */
    public boolean updateRiskSnapshot(String id, BigDecimal activeStop,
            BigDecimal nextTarget2r, BigDecimal currentClose, BigDecimal atr,
            java.time.Instant snapshotAt) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        int rows = jdbc.sql("""
                UPDATE watchlist_items
                   SET active_stop = :stop, next_target_2r = :tgt,
                       current_close = :close, atr = :atr, risk_snapshot_at = :ts
                 WHERE id = :id
                """)
                .param("stop", activeStop)
                .param("tgt", nextTarget2r)
                .param("close", currentClose)
                .param("atr", atr)
                .param("ts", java.sql.Timestamp.from(snapshotAt))
                .param("id", uuid)
                .update();
        return rows > 0;
    }

    /** Freezes the initial stop once. Returns true only if it was previously null. */
    public boolean updateInitialStop(String id, BigDecimal stop) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        int rows = jdbc.sql("""
                UPDATE watchlist_items SET initial_stop = :stop
                 WHERE id = :id AND initial_stop IS NULL
                """)
                .param("stop", stop).param("id", uuid).update();
        return rows > 0;
    }

    /** Sets the real entry/purchase date. isoDate is yyyy-MM-dd. */
    public boolean updateEntryDate(String id, String isoDate) {
        UUID uuid;
        try { uuid = UUID.fromString(id); }
        catch (IllegalArgumentException e) { return false; }
        int rows = jdbc.sql("UPDATE watchlist_items SET entry_date = CAST(:d AS DATE) WHERE id = :id")
                .param("d", isoDate).param("id", uuid).update();
        return rows > 0;
    }

    private List<Double> readDoubleList(String json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to deserialize JSON: {}", json, e);
            return List.of();
        }
    }
}
