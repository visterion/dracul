package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.SpinoffFiling;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpinoffScreenerTest {

    private final SpinoffScreener screener = new SpinoffScreener();

    @Test
    void dedupesSameSpinCoToMostRecentFiling() {
        var filings = List.of(
                new SpinoffFiling("SPN", "Acme Spinco Inc", "10-12B", LocalDate.of(2026, 5, 1), "u1"),
                new SpinoffFiling("SPN", "Acme Spinco Inc", "10-12B/A", LocalDate.of(2026, 5, 20), "u2"),
                new SpinoffFiling("", "Newco Holdings", "10-12B", LocalDate.of(2026, 5, 10), "u3"));

        List<SpinCandidate> out = screener.screen(filings);

        assertThat(out).hasSize(2);
        var acme = out.stream().filter(c -> "SPN".equals(c.symbol())).findFirst().orElseThrow();
        assertThat(acme.filingDate()).isEqualTo("2026-05-20");   // most recent kept
        assertThat(acme.formType()).isEqualTo("10-12B/A");
        var newco = out.stream().filter(c -> "Newco Holdings".equals(c.companyName())).findFirst().orElseThrow();
        assertThat(newco.symbol()).isEmpty();
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of())).isEmpty();
    }
}
