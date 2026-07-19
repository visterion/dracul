package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryTest {

    @Autowired ExecutorPositionRepository repo;
    @Autowired JdbcClient jdbc;

    @Test
    void insertReturnsIdAndFindsOpen() {
        String symbolA = "POS-A-" + UUID.randomUUID();
        String symbolB = "POS-B-" + UUID.randomUUID();

        var posA = new ExecutorPosition(null, "depot-1", symbolA, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS", "GUIDANCE_CUT"), "sig-a", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);
        var posB = new ExecutorPosition(null, "depot-1", symbolB, "BUY",
                new BigDecimal("5"), new BigDecimal("50.00"), new BigDecimal("45.00"),
                new BigDecimal("47.00"), 1, new BigDecimal("0.8"),
                List.of("STOP_HIT"), "sig-b", "strigoi-insider",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);

        long idA = repo.insert(posA);
        long idB = repo.insert(posB);

        assertThat(idA).isPositive();
        assertThat(idB).isPositive();
        assertThat(idA).isNotEqualTo(idB);

        assertThat(repo.countOpen()).isGreaterThanOrEqualTo(2);

        var open = repo.findOpen();
        assertThat(open).extracting(ExecutorPosition::symbol).contains(symbolA, symbolB);

        var found = open.stream().filter(p -> p.symbol().equals(symbolA)).findFirst().orElseThrow();
        assertThat(found.killCriteria()).containsExactlyInAnyOrder("EARNINGS_MISS", "GUIDANCE_CUT");
        assertThat(found.entryPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateMaintenanceReflected() {
        String symbol = "POS-MAINT-" + UUID.randomUUID();
        var pos = new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-maint", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);
        long id = repo.insert(pos);

        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 1,
                new BigDecimal("104"), "stop-9");

        var found = repo.findById(id);
        assertThat(found.highestPrice()).isEqualByComparingTo("110");
        assertThat(found.mfeR()).isEqualByComparingTo("1.6");
        assertThat(found.softConfirmCount()).isEqualTo(1);
        assertThat(found.activeStop()).isEqualByComparingTo("104");
        assertThat(found.stopOrderId()).isEqualTo("stop-9");
    }

    @Test
    void closeMovesOutOfOpen() {
        String symbol = "POS-CLOSE-" + UUID.randomUUID();
        var pos = new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-close", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null);
        long id = repo.insert(pos);

        repo.close(id, new BigDecimal("95"), new BigDecimal("-1.0"), "HARD_STOP");

        assertThat(repo.findOpen()).extracting(ExecutorPosition::symbol).doesNotContain(symbol);
        var found = repo.findById(id);
        assertThat(found.status()).isEqualTo("CLOSED");
        assertThat(found.realizedR()).isEqualByComparingTo("-1.0");
        assertThat(found.exitReason()).isEqualTo("HARD_STOP");
    }

    @Test
    void newColumnsRoundTripAndTranche2Update() {
        long id = repo.insert(openPosition("T2RT" + System.nanoTime()));
        ExecutorPosition p = repo.findById(id);
        assertThat(p.sector()).isEqualTo("Technology");
        assertThat(p.entryDayHigh()).isEqualByComparingTo("105.5");
        assertThat(p.tranche2OrderId()).isNull();

        repo.updateTranche2(id, new BigDecimal("20"), new BigDecimal("101.25"), "ord-2", "stop-2");
        ExecutorPosition after = repo.findById(id);
        assertThat(after.tranche()).isEqualTo(2);
        assertThat(after.qty()).isEqualByComparingTo("20");
        assertThat(after.entryPrice()).isEqualByComparingTo("101.25");
        assertThat(after.tranche2OrderId()).isEqualTo("ord-2");
        assertThat(after.tranche2StopOrderId()).isEqualTo("stop-2");
    }

    @Test
    void countEnteredSinceCountsOnlyRecentEntries() {
        Instant before = Instant.now().minusSeconds(5);
        long id = repo.insert(openPosition("CES" + System.nanoTime()));
        assertThat(id).isPositive();

        assertThat(repo.countEnteredSince(before)).isGreaterThanOrEqualTo(1);

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(repo.countEnteredSince(future)).isEqualTo(0);
    }

    @Test
    void recordTrimUpdatesQtyTrimCountAndResetsSoftConfirm() {
        long id = repo.insert(openPosition("TRIM" + System.nanoTime()));
        repo.updateMaintenance(id, new BigDecimal("110"), new BigDecimal("1.6"), 3,
                new BigDecimal("104"), "stop-9");
        assertThat(repo.findById(id).softConfirmCount()).isEqualTo(3);

        repo.recordTrim(id, new BigDecimal("50"), 1);

        var found = repo.findById(id);
        assertThat(found.qty()).isEqualByComparingTo("50");
        assertThat(found.trimCount()).isEqualTo(1);
        assertThat(found.softConfirmCount()).isEqualTo(0);
    }

    @Test
    void updateAdverseExtremePersists() {
        long id = repo.insert(openPosition("MAE" + System.nanoTime()));

        repo.updateAdverseExtreme(id, new BigDecimal("88.50"));

        assertThat(repo.findById(id).lowestPrice()).isEqualByComparingTo("88.50");
    }

    @Test
    void setEntryExpiresAtPersists() {
        long id = repo.insert(openPosition("GTD" + System.nanoTime()));
        Instant expiry = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        repo.setEntryExpiresAt(id, expiry);

        assertThat(repo.findById(id).entryExpiresAt()).isNotNull();
    }

    @Test
    void findOpenUnfilledPastExpiryReturnsOnlyExpiredOpenPositions() {
        long expiredId = repo.insert(openPosition("EXP-PAST" + System.nanoTime()));
        repo.setEntryExpiresAt(expiredId, Instant.now().minus(1, ChronoUnit.DAYS));

        long futureId = repo.insert(openPosition("EXP-FUTURE" + System.nanoTime()));
        repo.setEntryExpiresAt(futureId, Instant.now().plus(1, ChronoUnit.DAYS));

        long noExpiryId = repo.insert(openPosition("EXP-NONE" + System.nanoTime()));

        var expired = repo.findOpenUnfilledPastExpiry(Instant.now());

        assertThat(expired).extracting(ExecutorPosition::id).contains(expiredId);
        assertThat(expired).extracting(ExecutorPosition::id).doesNotContain(futureId, noExpiryId);
    }

    @Test
    void syncEntryPriceUpdatesOnlyEntryPrice() {
        String symbol = "PSMT-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "193.88");

        repo.syncEntryPrice(id, new BigDecimal("193.87"));

        ExecutorPosition p = repo.findById(id);
        assertThat(p.entryPrice()).isEqualByComparingTo("193.87");
        assertThat(p.status()).isEqualTo("OPEN");
    }

    @Test
    void markPendingExitStampsWithoutClosing() {
        String symbol = "PSMT-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "193.88");

        repo.markPendingExit(id, "STOP_BREACH", "ord-9", null, Instant.parse("2026-07-16T15:00:00Z"));

        ExecutorPosition p = repo.findById(id);
        assertThat(p.status()).isEqualTo("OPEN");
        assertThat(p.pendingExitReason()).isEqualTo("STOP_BREACH");
        assertThat(p.exitOrderId()).isEqualTo("ord-9");
    }

    @Test
    void secondOpenRowForSameConnectionSymbolFails() {
        String symbol = "PSMT-" + UUID.randomUUID();
        insertOpenPosition(symbol, "193.88");

        assertThatThrownBy(() -> insertOpenPosition(symbol.toLowerCase(), "193.90"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findOpenBySymbolReturnsOpenPositionForConnectionAndSymbol() {
        String symbol = "FOBS-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "193.88");

        ExecutorPosition found = repo.findOpenBySymbol("depot-1", symbol);

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.symbol()).isEqualTo(symbol);
        assertThat(found.status()).isEqualTo("OPEN");
    }

    @Test
    void findOpenBySymbolReturnsNullWhenNoOpenPositionMatches() {
        String symbol = "FOBS-NONE-" + UUID.randomUUID();

        assertThat(repo.findOpenBySymbol("depot-1", symbol)).isNull();
    }

    @Test
    void findOpenBySymbolIgnoresClosedPositions() {
        String symbol = "FOBS-CLOSED-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "193.88");
        repo.close(id, new BigDecimal("195"), new BigDecimal("0.2"), "TAKE_PROFIT");

        assertThat(repo.findOpenBySymbol("depot-1", symbol)).isNull();
    }

    @Test
    void closeWithSourcePersistsExitPriceSource() {
        String symbol = "PSMT-" + UUID.randomUUID();
        long id = insertOpenPosition(symbol, "193.88");

        repo.close(id, new BigDecimal("191.20"), new BigDecimal("-0.5"), "HARD_STOP", "FILL");

        ExecutorPosition p = repo.findById(id);
        assertThat(p.status()).isEqualTo("CLOSED");
        String exitPriceSource = jdbc.sql("SELECT exit_price_source FROM executor_position WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(exitPriceSource).isEqualTo("FILL");
    }

    private long insertOpenPosition(String symbol, String entryPrice) {
        return repo.insert(new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal(entryPrice), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null, 0, null, null, null, null, null, null));
    }

    private ExecutorPosition openPosition(String symbol) {
        return new ExecutorPosition(null, "depot-1", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                "Technology", new BigDecimal("105.5"), null, null, 0, null, null,
                null, null, null, null);
    }
}
