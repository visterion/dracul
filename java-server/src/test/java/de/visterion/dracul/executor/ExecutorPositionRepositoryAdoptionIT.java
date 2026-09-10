package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The two reads the SP4 adoption guard depends on: the book-slot lookup that matches the
 *  {@code lower(symbol)} unique index, and the stop-leg claim check that must see CLOSED rows too. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class ExecutorPositionRepositoryAdoptionIT {

    @Autowired ExecutorPositionRepository repo;
    @Autowired JdbcClient jdbc;

    /** ContainerConfig reuses one Postgres across IT classes; a sibling's rows would collide with
     *  the partial unique index on (connection, lower(symbol)) WHERE status='OPEN'. */
    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM executor_position_leg").update();
        jdbc.sql("DELETE FROM executor_position").update();
    }

    private long insert(String symbol, String status, String stopOrderId, String tranche2StopOrderId) {
        return repo.insert(new ExecutorPosition(null, "depot-1", symbol, "buy", new BigDecimal("10"),
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("95"), 1,
                new BigDecimal("1"), List.of("kc1"), "sig-1", "index-strigoi", null, null, status,
                "ord-entry", new BigDecimal("100"), null, 0, null, null, null, null, stopOrderId,
                null, null, null, tranche2StopOrderId, 0, null, null, null, null, null, null,
                false, null, null));
    }

    @Test
    void findOpenBySymbolIgnoreCase_matchesMixedCaseAndOnlyOpenRows() {
        long id = insert("ADOPTCO", "OPEN", null, null);

        assertThat(repo.findOpenBySymbolIgnoreCase("depot-1", "adoptco")).isNotNull()
                .extracting(ExecutorPosition::id).isEqualTo(id);
        assertThat(repo.findOpenBySymbolIgnoreCase("depot-1", "AdOpTcO")).isNotNull();
        assertThat(repo.findOpenBySymbolIgnoreCase("depot-1", "OTHERCO")).isNull();
        assertThat(repo.findOpenBySymbolIgnoreCase("depot-2", "ADOPTCO")).isNull();
    }

    @Test
    void findOpenBySymbolIgnoreCase_ignoresClosedRows() {
        insert("CLOSEDCO", "CLOSED", null, null);

        assertThat(repo.findOpenBySymbolIgnoreCase("depot-1", "closedco")).isNull();
    }

    @Test
    void stopOrderIdClaimed_trueForOpenAndClosedRowsOnEitherColumn() {
        insert("OPENCO", "OPEN", "ord-stop-open", null);
        insert("CLOSEDCO", "CLOSED", null, "ord-stop-t2-closed");

        assertThat(repo.stopOrderIdClaimed("ord-stop-open")).isTrue();
        assertThat(repo.stopOrderIdClaimed("ord-stop-t2-closed")).isTrue();
        assertThat(repo.stopOrderIdClaimed("ord-unclaimed")).isFalse();
        assertThat(repo.stopOrderIdClaimed(null)).isFalse();
    }
}
