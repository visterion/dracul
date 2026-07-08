package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test — no Spring context. OrderGuard is the final code-enforced guard on a
 * broker order the LLM proposed.
 */
class OrderGuardTest {

    private final OrderGuard guard = new OrderGuard();

    @Test
    void passesValidLong() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("95"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void passesValidShort() {
        OrderGuard.Result result = guard.check("SELL", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("105"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isTrue();
        assertThat(result.reason()).isNull();
    }

    @Test
    void rejectsNonAllowedConnection() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("95"), "saxo-live", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NON_SIM_CONNECTION);
    }

    @Test
    void rejectsNullConnection() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("95"), null, "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NON_SIM_CONNECTION);
    }

    @Test
    void rejectsZeroQty() {
        OrderGuard.Result result = guard.check("BUY", BigDecimal.ZERO, new BigDecimal("100"),
                new BigDecimal("95"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void rejectsNegativeQty() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("-5"), new BigDecimal("100"),
                new BigDecimal("95"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.SCHEMA_INVALID);
    }

    @Test
    void rejectsMissingStop() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                null, "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    @Test
    void rejectsNonPositiveStop() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                BigDecimal.ZERO, "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    @Test
    void rejectsStopOnWrongSideLong() {
        OrderGuard.Result result = guard.check("BUY", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("105"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    @Test
    void rejectsStopOnWrongSideShort() {
        OrderGuard.Result result = guard.check("SELL", new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("95"), "saxo-sim", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NO_STOP);
    }

    @Test
    void connectionCheckedBeforeQty() {
        OrderGuard.Result result = guard.check("BUY", BigDecimal.ZERO, new BigDecimal("100"),
                new BigDecimal("95"), "saxo-live", "saxo-sim");
        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo(RejectReason.NON_SIM_CONNECTION);
    }
}
