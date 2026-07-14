package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.AccountSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CURRENCY_MISMATCH veto (B5b): the executor is single-currency in this slice, so a bracket may
 * only be sized in the configured account/instrument currency. A find whose instrument trades in
 * another currency — or whose quote carried no currency at all — must be vetoed, never silently
 * treated as USD. The find is still surfaced/watchlisted/given a Verdict elsewhere; only the order
 * is withheld.
 */
class ExecutorCurrencyVetoTest {

    private final VetoService vetoService = new VetoService();

    private ExecutorSignal validSignal() {
        return new ExecutorSignal("sig-1", "strigoi-test", "v1", "ACME", "LONG", 0.8, "PEAD",
                List.of("Close below 90.00"), "20d", BigDecimal.valueOf(50), "PENDING",
                "2026-07-08T00:00:00Z");
    }

    /** A pass-everything context (mirrors VetoServiceTest defaults) with a caller-chosen instrument
     *  quote currency. */
    private EntryContext ctxWithCurrency(String quoteCurrency) {
        return new EntryContext(
                new AccountSnapshot(BigDecimal.valueOf(100000), BigDecimal.valueOf(100000), "USD"),
                BigDecimal.valueOf(50), BigDecimal.valueOf(2), null, BigDecimal.valueOf(1_000_000),
                null, "Tech", List.of(), List.of(), List.of(), 0, 1L, BigDecimal.valueOf(1000),
                BigDecimal.valueOf(10000), BigDecimal.ZERO, BigDecimal.ZERO, Map.of(),
                BigDecimal.ONE, List.of(), quoteCurrency);
    }

    private Sizing sizing() {
        return new Sizing(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.valueOf(100),
                BigDecimal.ZERO, BigDecimal.ZERO, true, "entry - 2.5 x ATR22");
    }

    /** config instrument-currency = USD. */
    private VetoConfig cfgUsd() {
        return new VetoConfig(0.6, 5, BigDecimal.valueOf(10000), 0.06, 3,
                BigDecimal.valueOf(5), 20, 5, 2.0, 3, 10, 0.0, 3.0, "USD");
    }

    private VetoResult result(VetoService.Outcome out, String check) {
        return out.results().stream().filter(r -> r.check().equals(check)).findFirst().orElseThrow();
    }

    @Test
    void eurInstrumentAgainstUsdConfig_vetoesWithCurrencyMismatch() {
        VetoService.Outcome out = vetoService.evaluate(validSignal(), ctxWithCurrency("EUR"), sizing(), cfgUsd());

        assertThat(out.passed()).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.CURRENCY_MISMATCH);
        assertThat(result(out, "CURRENCY_MISMATCH").passed()).isFalse();
        assertThat(result(out, "CURRENCY_MISMATCH").measured()).isEqualTo("EUR != USD");
    }

    @Test
    void nullQuoteCurrency_vetoes_notSilentUsdPass() {
        VetoService.Outcome out = vetoService.evaluate(validSignal(), ctxWithCurrency(null), sizing(), cfgUsd());

        assertThat(out.passed()).isFalse();
        assertThat(out.firstFailure()).isEqualTo(RejectReason.CURRENCY_MISMATCH);
        assertThat(result(out, "CURRENCY_MISMATCH").measured()).isEqualTo("unknown != USD");
    }

    @Test
    void matchingUsd_proceeds_unchanged() {
        VetoService.Outcome out = vetoService.evaluate(validSignal(), ctxWithCurrency("USD"), sizing(), cfgUsd());

        assertThat(out.passed()).isTrue();
        assertThat(out.firstFailure()).isNull();
        assertThat(result(out, "CURRENCY_MISMATCH").passed()).isTrue();
        assertThat(result(out, "CURRENCY_MISMATCH").measured()).isEqualTo("USD == USD");
    }

    @Test
    void currencyMatchIsCaseInsensitive() {
        VetoService.Outcome out = vetoService.evaluate(validSignal(), ctxWithCurrency("usd"), sizing(), cfgUsd());

        assertThat(result(out, "CURRENCY_MISMATCH").passed()).isTrue();
    }
}
