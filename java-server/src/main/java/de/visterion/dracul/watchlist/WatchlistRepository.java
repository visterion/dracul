package de.visterion.dracul.watchlist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class WatchlistRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public WatchlistRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<WatchlistItem> findAllByUser(String userId) {
        var items = jdbc.sql("""
                SELECT id, ticker, company_name, current_price, day_change_percent,
                       status, added_at, tag, verdict_id, price_history_30d
                FROM watchlist_items
                WHERE user_id = :userId
                ORDER BY added_at DESC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new WatchlistItem(
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
                        readDoubleList(rs.getString("price_history_30d"))
                ))
                .list();

        if (items.isEmpty()) return items;

        var alertsByItem = new HashMap<String, List<WatchlistAlert>>();
        jdbc.sql("""
                SELECT id, watchlist_item_id, at, message, level
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
                                    rs.getString("level")
                            ));
                    return null;
                })
                .list();

        return items.stream()
                .map(item -> new WatchlistItem(
                        item.id(), item.ticker(), item.companyName(),
                        item.currentPrice(), item.dayChangePercent(),
                        item.status(), item.addedAt(), item.tag(),
                        item.verdictId(),
                        alertsByItem.getOrDefault(item.id(), List.of()),
                        item.priceHistory30d()
                ))
                .toList();
    }

    private List<Double> readDoubleList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
