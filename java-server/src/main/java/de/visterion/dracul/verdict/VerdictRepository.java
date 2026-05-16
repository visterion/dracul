package de.visterion.dracul.verdict;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class VerdictRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public VerdictRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Verdict> findAllByUser(String userId) {
        return jdbc.sql("""
                SELECT id, symbol, company_name, contributing_strigoi,
                       consensus_score, summary, created_at
                FROM verdicts
                WHERE user_id = :userId
                ORDER BY created_at DESC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new Verdict(
                        rs.getString("id"),
                        rs.getString("symbol"),
                        rs.getString("company_name"),
                        readList(rs.getString("contributing_strigoi")),
                        rs.getDouble("consensus_score"),
                        rs.getString("summary"),
                        rs.getString("created_at")
                ))
                .list();
    }

    public Optional<VerdictDetail> findDetailById(String id) {
        return jdbc.sql("""
                SELECT id, symbol, company_name, contributing_strigoi, consensus_score,
                       summary, created_at, anomaly_types, current_price, avg_confidence,
                       horizon, signals, risks, contributing_details
                FROM verdicts
                WHERE id::text = :id
                """)
                .param("id", id)
                .query((rs, rowNum) -> new VerdictDetail(
                        rs.getString("id"),
                        rs.getString("symbol"),
                        rs.getString("company_name"),
                        readList(rs.getString("contributing_strigoi")),
                        rs.getDouble("consensus_score"),
                        rs.getString("summary"),
                        rs.getString("created_at"),
                        readList(rs.getString("anomaly_types")),
                        rs.getDouble("current_price"),
                        rs.getDouble("avg_confidence"),
                        rs.getString("horizon"),
                        readList(rs.getString("signals")),
                        readList(rs.getString("risks")),
                        readDetails(rs.getString("contributing_details"))
                ))
                .optional();
    }

    public Optional<String> findLatestCreatedAt(String userId) {
        return jdbc.sql("""
                SELECT created_at FROM verdicts
                WHERE user_id = :userId
                ORDER BY created_at DESC LIMIT 1
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> rs.getString("created_at"))
                .optional();
    }

    private List<String> readList(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ContributingStrigoiDetail> readDetails(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
