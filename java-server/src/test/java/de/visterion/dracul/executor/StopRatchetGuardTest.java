package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure guard: a stop may only ever move in the position's favor, never against it. */
class StopRatchetGuardTest {

    private final StopRatchetGuard guard = new StopRatchetGuard();

    @Test
    void buyRaise_permitted() {
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("104"), "BUY")).isTrue();
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("104"), "buy")).isTrue();
    }

    @Test
    void buyEqualOrLower_denied() {
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("95"), "BUY")).isFalse();
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("90"), "BUY")).isFalse();
    }

    @Test
    void sellLower_permitted() {
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("90"), "SELL")).isTrue();
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("90"), "sell")).isTrue();
    }

    @Test
    void sellEqualOrHigher_denied() {
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("95"), "SELL")).isFalse();
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("100"), "SELL")).isFalse();
    }

    @Test
    void unknownOrNullSide_denied() {
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("104"), "HOLD")).isFalse();
        assertThat(guard.permit(new BigDecimal("95"), new BigDecimal("104"), null)).isFalse();
    }

    @Test
    void nullPrices_denied() {
        assertThat(guard.permit(null, new BigDecimal("104"), "BUY")).isFalse();
        assertThat(guard.permit(new BigDecimal("95"), null, "BUY")).isFalse();
        assertThat(guard.permit(null, null, "BUY")).isFalse();
    }
}
