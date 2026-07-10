package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryTest {

    @Autowired ExecutorPositionRepository repo;

    @Test
    void insertReturnsIdAndFindsOpen() {
        String symbolA = "POS-A-" + UUID.randomUUID();
        String symbolB = "POS-B-" + UUID.randomUUID();

        var posA = new ExecutorPosition(null, "saxo-sim", symbolA, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS", "GUIDANCE_CUT"), "sig-a", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null);
        var posB = new ExecutorPosition(null, "saxo-sim", symbolB, "BUY",
                new BigDecimal("5"), new BigDecimal("50.00"), new BigDecimal("45.00"),
                new BigDecimal("47.00"), 1, new BigDecimal("0.8"),
                List.of("STOP_HIT"), "sig-b", "strigoi-insider",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null);

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
        var pos = new ExecutorPosition(null, "saxo-sim", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-maint", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null);
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
        var pos = new ExecutorPosition(null, "saxo-sim", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-close", "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                null, null, null, null);
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

    private ExecutorPosition openPosition(String symbol) {
        return new ExecutorPosition(null, "saxo-sim", symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100.00"), new BigDecimal("90.00"),
                new BigDecimal("95.00"), 1, new BigDecimal("1.5"),
                List.of("EARNINGS_MISS"), "sig-" + symbol, "strigoi-spin",
                null, null, "OPEN", null,
                null, null, 0, null, null, null, null, null,
                "Technology", new BigDecimal("105.5"), null, null);
    }
}
