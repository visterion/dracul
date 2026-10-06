package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V54 against real Postgres (spec 2026-10-06 §3.4, §7, §12 IT). Unique connection + symbol per
 *  test: the container is reused across classes and runs. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class SavingsPlanRepositoryIT {

    @Autowired SavingsPlanRepository repo;
    @Autowired ExecutorPositionRepository positions;
    @Autowired ExecutorPositionLegRepository legs;
    @Autowired DecisionLogRepository decisions;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetLease() {
        jdbc.sql("UPDATE savings_plan_lock SET holder = NULL, until = '-infinity' WHERE id = 1").update();
    }

    private long conviction(String symbol, int trimCount) {
        var base = ExecutorPositionFixtures.withoutKillLevel(null, "sp-" + UUID.randomUUID(),
                symbol, "BUY", new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"),
                new BigDecimal("65.00"), 1, null, List.of("X"), "sig-" + symbol, "strigoi-tech",
                null, null, "OPEN", "brk-" + symbol, new BigDecimal("100"), null, 0, null, null,
                null, null, "stop-" + symbol, null, null, null, null, trimCount, null, null, null,
                null, null, null, false, new BigDecimal("80.00"), "2026-10-01T14:30:00Z");
        return positions.insert(ExecutorPositionFixtures.conviction(base));
    }

    private static String sym() {
        return "TECH" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    @Test
    void v54SeedsTheLockRowAtMinusInfinity() {
        Integer rows = jdbc.sql("SELECT count(*) FROM savings_plan_lock WHERE id = 1")
                .query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO savings_plan_lock (id) VALUES (2)").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leaseIsExclusiveRenewableAndReleasedOnlyByItsHolder() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        assertThat(repo.leaseHolder()).isNull();
        assertThat(repo.tryAcquireLease(a)).isTrue();
        assertThat(repo.leaseHolder()).isEqualTo(a);
        assertThat(repo.tryAcquireLease(b)).isFalse();
        assertThat(repo.renewLease(b)).isFalse();
        assertThat(repo.renewLease(a)).isTrue();
        repo.releaseLease(b);                       // not the holder: no effect
        assertThat(repo.tryAcquireLease(b)).isFalse();
        repo.releaseLease(a);
        assertThat(repo.tryAcquireLease(b)).isTrue();
    }

    @Test
    void aStaleLeaseIsTakenOver() {
        String a = UUID.randomUUID().toString();
        assertThat(repo.tryAcquireLease(a)).isTrue();
        jdbc.sql("UPDATE savings_plan_lock SET until = now() - interval '1 minute' WHERE id = 1").update();
        assertThat(repo.tryAcquireLease(UUID.randomUUID().toString())).isTrue();
        assertThat(repo.renewLease(a)).isFalse();
    }

    @Test
    void monthPositionIsUniqueAndTheSecondInsertReturnsNull() {
        String symbol = sym();
        long pid = conviction(symbol, 0);
        Long first = repo.insertPlacing("2026-11", pid, symbol, new BigDecimal("9"),
                new BigDecimal("112.20"), new BigDecimal("100.98"), new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("65"), "gtc");
        Long second = repo.insertSkipped("2026-11", pid, symbol, new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("65"), "CARRY");
        assertThat(first).isNotNull();
        assertThat(second).isNull();
        SavingsBuy row = repo.findById(first);
        assertThat(row.status()).isEqualTo(SavingsBuy.PLACING);
        assertThat(row.clientRef()).isEqualTo("sp-" + pid + "-202611");
        assertThat(row.inFlight()).isTrue();
        assertThat(repo.existsForMonth("2026-11", pid)).isTrue();
        assertThat(repo.existsForMonth("2026-12", pid)).isFalse();
    }

    @Test
    void statusAndSkipChecksHold() {
        String symbol = sym();
        long pid = conviction(symbol, 0);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO savings_plan_buy (month, position_id, symbol, qty, limit_price, limit_eur,
                    client_ref, qty_before, avg_before, stop_before, status)
                VALUES ('2026-11', :pid, :s, 1, 10, 9, :ref, 1, 10, 6.5, 'BOGUS')
                """).param("pid", pid).param("s", symbol).param("ref", "x-" + UUID.randomUUID()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO savings_plan_buy (month, position_id, symbol, qty, client_ref,
                    qty_before, avg_before, stop_before, status)
                VALUES ('2026-11', :pid, :s, 1, :ref, 1, 10, 6.5, 'PLACING')
                """).param("pid", pid).param("s", symbol).param("ref", "y-" + UUID.randomUUID()).update())
                .as("a non-SKIPPED row needs limit_price and limit_eur")
                .isInstanceOf(DataIntegrityViolationException.class);
        Long skipped = repo.insertSkipped("2026-11", pid, symbol, new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("65"), "DATA");
        SavingsBuy row = repo.findById(skipped);
        assertThat(row.status()).isEqualTo(SavingsBuy.SKIPPED);
        assertThat(row.qty()).isEqualByComparingTo("0");
        assertThat(row.skipReason()).isEqualTo("DATA");
        assertThat(row.inFlight()).isFalse();
    }

    @Test
    void casTransitionsMoveOnlyFromTheExpectedStatus() {
        String symbol = sym();
        long pid = conviction(symbol, 0);
        long id = repo.insertPlacing("2026-11", pid, symbol, new BigDecimal("9"),
                new BigDecimal("112.20"), new BigDecimal("100.98"), new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("65"), "gtc");

        assertThat(repo.markPlaced(id, "brk-9", "child-9")).isTrue();
        assertThat(repo.markPlaced(id, "brk-x", null)).isFalse();
        assertThat(repo.markConsolidating(id, SavingsBuy.PLACED, new BigDecimal("19"),
                new BigDecimal("68.76"), new BigDecimal("105.778947"))).isTrue();
        assertThat(repo.findById(id).status()).isEqualTo(SavingsBuy.CONSOLIDATING);
        assertThat(repo.setNewStopOrderId(id, SavingsBuy.CONSOLIDATING, "pstop-1")).isTrue();
        assertThat(repo.transition(id, SavingsBuy.CONSOLIDATING, SavingsBuy.UNPROTECTED)).isTrue();
        assertThat(repo.markConsolidating(id, SavingsBuy.UNPROTECTED, new BigDecimal("19"),
                new BigDecimal("68.76"), new BigDecimal("105.778947"))).isTrue();
        assertThat(repo.findById(id).status())
                .as("an UNPROTECTED row stays UNPROTECTED while it is re-targeted")
                .isEqualTo(SavingsBuy.UNPROTECTED);
        assertThat(repo.markWindowStop(id, new BigDecimal("2"))).isTrue();
        assertThat(repo.markWindowStop(id, new BigDecimal("2"))).isFalse();
        assertThat(repo.finish(id, SavingsBuy.PLACED, SavingsBuy.CONSOLIDATED, null, null, null, null)).isFalse();
        assertThat(repo.finish(id, SavingsBuy.UNPROTECTED, SavingsBuy.CONSOLIDATED,
                new BigDecimal("9"), new BigDecimal("112.20"), new BigDecimal("105.778947"),
                new BigDecimal("68.76"))).isTrue();

        SavingsBuy done = repo.findById(id);
        assertThat(done.status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        assertThat(done.entryOrderId()).isEqualTo("brk-9");
        assertThat(done.childStopOrderId()).isEqualTo("child-9");
        assertThat(done.newStopOrderId()).isEqualTo("pstop-1");
        assertThat(done.windowStopQty()).isEqualByComparingTo("2");
        assertThat(done.targetQty()).isEqualByComparingTo("19");
        assertThat(done.fillQty()).isEqualByComparingTo("9");
        assertThat(done.stopAfter()).isEqualByComparingTo("68.76");
        assertThat(repo.findInFlightByPosition(pid)).isNull();
    }

    @Test
    void emergencyExitCasAndInFlightReads() {
        String symbol = sym();
        long pid = conviction(symbol, 0);
        long id = repo.insertPlacing("2026-11", pid, symbol, new BigDecimal("5"),
                new BigDecimal("50"), new BigDecimal("45"), new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("65"), "day");
        repo.markPlaced(id, "brk-5", null);
        assertThat(repo.markEmergencyExit(id, SavingsBuy.PLACED, new BigDecimal("5"),
                new BigDecimal("49.50"))).isTrue();
        assertThat(repo.findInFlightByPosition(pid).status()).isEqualTo(SavingsBuy.EMERGENCY_EXIT);
        assertThat(repo.inFlightStatusByPosition()).containsEntry(pid, SavingsBuy.EMERGENCY_EXIT);
        assertThat(repo.findInFlight()).extracting(SavingsBuy::id).contains(id);
        assertThat(repo.positionsTouchedSince(Instant.now().minusSeconds(60))).contains(pid);
        assertThat(repo.findByMonth("2026-11")).extracting(SavingsBuy::id).contains(id);
    }

    @Test
    void carryAccruesIsSetAndTheStaleCleanupDropsTrimmedPositions() {
        long open = conviction(sym(), 0);
        long trimmed = conviction(sym(), 1);
        assertThat(repo.carryOf(open)).isEqualByComparingTo("0");
        repo.addCarry(open, new BigDecimal("1000"));
        repo.addCarry(open, new BigDecimal("91.18"));
        assertThat(repo.carryOf(open)).isEqualByComparingTo("1091.18");
        repo.setCarry(open, new BigDecimal("8.56"));
        assertThat(repo.carryOf(open)).isEqualByComparingTo("8.56");
        repo.setCarry(trimmed, new BigDecimal("500"));
        assertThat(repo.deleteStaleCarry()).isGreaterThanOrEqualTo(1);
        assertThat(repo.carryOf(trimmed)).isEqualByComparingTo("0");
        assertThat(repo.carryOf(open)).isEqualByComparingTo("8.56");
        repo.deleteCarry(open);
        assertThat(repo.carryOf(open)).isEqualByComparingTo("0");
    }

    @Test
    void monthRowFirstWriterWinsAndFlagsAreSetOnce() {
        String month = "9" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(repo.markMissedAlerted(month)).isTrue();
        assertThat(repo.markMissedAlerted(month)).isFalse();
        SavingsMonth m = repo.ensureMonth(month, new BigDecimal("2000"), 4);
        assertThat(m.monthAmountEur()).isEqualByComparingTo("2000");
        assertThat(m.candidateCount()).isEqualTo(4);
        SavingsMonth again = repo.ensureMonth(month, new BigDecimal("1500"), 3);
        assertThat(again.monthAmountEur()).as("a catch-up pass reuses the first amount").isEqualByComparingTo("2000");
        assertThat(again.candidateCount()).isEqualTo(4);
        assertThat(repo.completeMonth(month)).isTrue();
        assertThat(repo.completeMonth(month)).isFalse();
        assertThat(repo.findMonth(month).completedAt()).isNotNull();
        assertThat(repo.findMonth(month).missedAlertedAt()).isNotNull();
    }

    @Test
    void positionAndLegBookingMethodsRoundTrip() {
        String symbol = sym();
        long pid = conviction(symbol, 0);
        long legId = legs.insert(new ExecutorPositionLeg(null, pid, 1, "brk-" + symbol,
                "stop-" + symbol, new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));

        positions.bookSavingsQtyAndAvg(pid, new BigDecimal("19"), new BigDecimal("105.778947"));
        positions.bookSavingsStop(pid, "pstop-7", new BigDecimal("68.76"), new BigDecimal("84.63"), true);
        legs.setStopAndQty(legId, "pstop-7", new BigDecimal("19"));

        ExecutorPosition p = positions.findById(pid);
        assertThat(p.qty()).isEqualByComparingTo("19");
        assertThat(p.entryPrice()).isEqualByComparingTo("105.778947");
        assertThat(p.stopOrderId()).isEqualTo("pstop-7");
        assertThat(p.activeStop()).isEqualByComparingTo("68.76");
        assertThat(p.brokerStop()).isEqualByComparingTo("84.63");
        assertThat(p.brokerStopNarrow()).isTrue();
        assertThat(p.initialStop()).as("initial_stop is immutable").isEqualByComparingTo("65.00");
        ExecutorPositionLeg leg = legs.findOpenByPosition(pid).getFirst();
        assertThat(leg.stopOrderId()).isEqualTo("pstop-7");
        assertThat(leg.qty()).isEqualByComparingTo("19");

        positions.setStopOrderId(pid, null);
        assertThat(positions.findById(pid).stopOrderId()).isNull();
    }

    @Test
    void decisionRowsAreCountedPerSavingsBuy() {
        long buyId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var orderJson = mapper.createObjectNode().put("savings_buy_id", buyId);
        decisions.insert(new DecisionLog(null, "run-it", "exec-test", "SAVINGS_PLAN", null, null,
                null, "SYNTH", null, null, "ESCALATE", "CONSOLIDATE_CANCEL_FAILED", orderJson,
                "synthetic", null, null, null));
        assertThat(decisions.countByReasonCodeForSavingsBuy("CONSOLIDATE_CANCEL_FAILED", buyId)).isEqualTo(1);
        assertThat(decisions.countByReasonCodeForSavingsBuy("CONSOLIDATE_STUCK", buyId)).isZero();
    }
}
