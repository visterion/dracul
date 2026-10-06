package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.executor.broker.BrokerPosition;
import de.visterion.dracul.executor.broker.FakeExecutionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static de.visterion.dracul.executor.SavingsFixtures.liveStop;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2026-10-06 §3.4, §12 IT: two concurrent maintenance passes over real Postgres — the lease and
 *  the CAS let exactly one of them cancel and place. */
@SpringBootTest
@Import({ContainerConfig.class, SavingsPlanConcurrencyIT.FakeGatewayConfig.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class SavingsPlanConcurrencyIT {

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeExecutionGateway fakeExecutionGateway() {
            return new FakeExecutionGateway();
        }
    }

    @Autowired FakeExecutionGateway gateway;
    @Autowired SavingsPlanService savingsPlan;
    @Autowired SavingsConsolidator consolidator;
    @Autowired SavingsPlanRepository savingsRepo;
    @Autowired ExecutorPositionRepository positions;
    @Autowired ExecutorPositionLegRepository legs;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("UPDATE savings_plan_lock SET holder = NULL, until = '-infinity' WHERE id = 1").update();
        gateway.cancelRemovesOrder = true;
        gateway.protectiveStops.clear();
        gateway.cancelledOrderIds.clear();
    }

    @Test
    void twoConcurrentPassesCancelAndPlaceExactlyOnce() throws Exception {
        String connection = "sp-" + UUID.randomUUID();
        String symbol = "TECH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        var base = ExecutorPositionFixtures.withoutKillLevel(null, connection, symbol, "BUY",
                new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"), new BigDecimal("65.00"),
                1, null, List.of("X"), "sig-" + symbol, "strigoi-tech", null, null, "OPEN", "brk-" + symbol,
                new BigDecimal("100"), null, 0, null, null, null, null, "stop-" + symbol, null, null, null,
                null, 0, null, null, null, null, null, null, false, new BigDecimal("65.00"),
                "2026-10-01T14:30:00Z");
        long pid = positions.insert(ExecutorPositionFixtures.conviction(base));
        legs.insert(new ExecutorPositionLeg(null, pid, 1, "brk-" + symbol, "stop-" + symbol,
                new BigDecimal("10"), ExecutorPositionLeg.OPEN, null, null, null));
        String month = "2026-" + UUID.randomUUID().toString().substring(0, 4);
        Long rowId = savingsRepo.insertPlacing(month, pid, symbol, new BigDecimal("9"), new BigDecimal("112.20"),
                new BigDecimal("100.98"), new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("65.00"), "gtc");
        savingsRepo.markPlaced(rowId, "brk9-" + symbol, "child9-" + symbol);
        jdbc.sql("UPDATE savings_plan_buy SET created_at = now() - interval '3 days' WHERE id = :id")
                .param("id", rowId).update();
        gateway.seedPosition(new BrokerPosition(symbol, "BUY", new BigDecimal("19"),
                new BigDecimal("105.77894736842105"), new BigDecimal("110"), null));
        gateway.seedOrder(liveStop("stop-" + symbol, symbol, "10", "65.00"));
        gateway.seedOrder(liveStop("child9-" + symbol, symbol, "9", "89.76"));
        Instant now = Instant.now();

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> pass = () -> {
            String uuid = UUID.randomUUID().toString();
            start.await();
            if (!savingsPlan.tryLease(uuid)) return false;
            try {
                consolidator.consolidateStage(connection, "run-it", uuid, now);
                return true;
            } finally {
                savingsPlan.releaseLease(uuid);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> a = pool.submit(pass);
        Future<Boolean> b = pool.submit(pass);
        start.countDown();
        a.get();
        b.get();
        pool.shutdown();

        assertThat(gateway.protectiveStops).hasSize(1);
        assertThat(gateway.cancelledOrderIds).containsExactlyInAnyOrder("stop-" + symbol, "child9-" + symbol);
        assertThat(savingsRepo.findById(rowId).status()).isEqualTo(SavingsBuy.CONSOLIDATED);
        ExecutorPosition booked = positions.findById(pid);
        assertThat(booked.qty()).isEqualByComparingTo("19");
        assertThat(booked.activeStop()).isEqualByComparingTo("68.76");
        assertThat(booked.initialStop()).isEqualByComparingTo("65.00");
        assertThat(legs.findOpenByPosition(pid)).singleElement()
                .satisfies(l -> assertThat(l.qty()).isEqualByComparingTo("19"));
    }
}
