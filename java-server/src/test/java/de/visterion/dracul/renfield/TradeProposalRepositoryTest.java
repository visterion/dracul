package de.visterion.dracul.renfield;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class TradeProposalRepositoryTest {

    @Autowired TradeProposalRepository repo;
    @Autowired JdbcClient jdbc;

    /** Inserts a row directly with an explicit created_at, bypassing the repository's
     *  now()-only insert — needed to control ordering/age for the window-function tests. */
    private void insertRawAt(String owner, String symbol, String action, BigDecimal confidence,
            String runId, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO trade_proposals
                  (id, owner, symbol, action, rationale, run_id, confidence, created_at)
                VALUES (:id, :o, :s, :a, 'r', :run, :c, :createdAt)
                ON CONFLICT (run_id, symbol) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("o", owner)
                .param("s", symbol)
                .param("a", action)
                .param("run", runId)
                .param("c", confidence)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();
    }

    @Test
    void newsSentimentArrayRoundTripsAsJsonb() {
        String owner = "alice@example.com";
        String symbol = "SENT" + System.nanoTime();
        String json = """
                [{"headline":"ACME beats estimates","sentiment":"positive"},
                 {"headline":"ACME faces lawsuit","sentiment":"negative"}]
                """;

        int inserted = repo.insert(owner, symbol, "buy", "10-12", "9.50",
                new BigDecimal("0.7"), "thesis", "note", "run-" + System.nanoTime(), json);
        assertThat(inserted).isEqualTo(1);

        var found = repo.findRecent(owner, 1).stream()
                .filter(p -> p.symbol().equals(symbol)).findFirst().orElseThrow();
        assertThat(found.newsSentiment()).isNotNull();
        assertThat(found.newsSentiment().isArray()).isTrue();
        assertThat(found.newsSentiment().get(0).path("headline").asText())
                .isEqualTo("ACME beats estimates");
        assertThat(found.newsSentiment().get(1).path("sentiment").asText())
                .isEqualTo("negative");
    }

    @Test
    void absentNewsSentimentStoresNull() {
        String owner = "alice@example.com";
        String symbol = "NOSENT" + System.nanoTime();

        int inserted = repo.insert(owner, symbol, "hold", "", "",
                null, "thesis", "note", "run-" + System.nanoTime(), null);
        assertThat(inserted).isEqualTo(1);

        var found = repo.findRecent(owner, 1).stream()
                .filter(p -> p.symbol().equals(symbol)).findFirst().orElseThrow();
        assertThat(found.newsSentiment()).isNull();
    }

    @Test
    void duplicateRunIdSymbolReturnsZero() {
        String owner = "alice@example.com";
        String symbol = "DUP" + System.nanoTime();
        String runId = "run-" + System.nanoTime();

        int first = repo.insert(owner, symbol, "buy", "10-12", "9.50",
                new BigDecimal("0.7"), "first thesis", "note", runId, null);
        assertThat(first).isEqualTo(1);

        int second = repo.insert(owner, symbol, "sell", "20-22", "19.50",
                new BigDecimal("0.3"), "second thesis", "note2", runId, null);
        assertThat(second).isZero();

        var found = repo.findRecent(owner, 1).stream()
                .filter(p -> p.symbol().equals(symbol)).toList();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).action()).isEqualTo("buy");
        assertThat(found.get(0).rationale()).isEqualTo("first thesis");
    }

    @Test
    void findRecentIsOwnerScoped() {
        String ownerA = "alice@example.com";
        String ownerB = "bob@example.com";
        String symbol = "OWN" + System.nanoTime();

        int idA = repo.insert(ownerA, symbol, "buy", "", "", null, "r", "note",
                "run-a-" + System.nanoTime(), null);
        int idB = repo.insert(ownerB, symbol, "buy", "", "", null, "r", "note",
                "run-b-" + System.nanoTime(), null);
        assertThat(idA).isEqualTo(1);
        assertThat(idB).isEqualTo(1);

        var forA = repo.findRecent(ownerA, 1).stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(TradeProposal::id)
                .collect(java.util.stream.Collectors.toSet());
        var forB = repo.findRecent(ownerB, 1).stream()
                .filter(p -> p.symbol().equals(symbol))
                .map(TradeProposal::id)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(forA).hasSize(1);
        assertThat(forB).hasSize(1);
        assertThat(java.util.Collections.disjoint(forA, forB)).isTrue();
    }

    @Test
    void findPriorBySymbolsReturnsAtMostFivePerSymbol() {
        String owner = "alice@example.com";
        String symbolA = "PRIORA" + System.nanoTime();
        String symbolB = "PRIORB" + System.nanoTime();
        Instant now = Instant.now();

        for (int i = 0; i < 6; i++) {
            insertRawAt(owner, symbolA, "buy", new BigDecimal("0.5"),
                    "run-a-" + i + "-" + System.nanoTime(), now.minus(i, ChronoUnit.HOURS));
        }
        for (int i = 0; i < 5; i++) {
            insertRawAt(owner, symbolB, "hold", new BigDecimal("0.4"),
                    "run-b-" + i + "-" + System.nanoTime(), now.minus(i, ChronoUnit.HOURS));
        }

        Map<String, List<PriorProposal>> prior = repo.findPriorBySymbols(owner, List.of(symbolA, symbolB));

        assertThat(prior.get(symbolA)).hasSize(5);
        assertThat(prior.get(symbolB)).hasSize(5);
    }

    @Test
    void findPriorBySymbolsIgnoresRowsOlderThanTheWindow() {
        String owner = "alice@example.com";
        String symbol = "WINDOW" + System.nanoTime();
        Instant now = Instant.now();

        insertRawAt(owner, symbol, "buy", new BigDecimal("0.6"),
                "run-recent-" + System.nanoTime(), now.minus(2, ChronoUnit.DAYS));
        insertRawAt(owner, symbol, "sell", new BigDecimal("0.6"),
                "run-old-" + System.nanoTime(), now.minus(11, ChronoUnit.DAYS));

        Map<String, List<PriorProposal>> prior = repo.findPriorBySymbols(owner, List.of(symbol));

        assertThat(prior.get(symbol)).hasSize(1);
        assertThat(prior.get(symbol).get(0).action()).isEqualTo("buy");
    }

    @Test
    void findPriorBySymbolsEmptyListReturnsEmptyMap() {
        assertThat(repo.findPriorBySymbols("alice@example.com", List.of())).isEmpty();
        assertThat(repo.findPriorBySymbols("alice@example.com", null)).isEmpty();
    }
}
