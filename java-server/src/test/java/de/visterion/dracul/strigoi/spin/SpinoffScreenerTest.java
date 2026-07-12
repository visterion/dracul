package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.SpinoffFiling;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpinoffScreenerTest {

    private final SpinoffScreener screener = new SpinoffScreener();

    @Test
    void dedupesSameSpinCoByCikCollapsingAmendments() {
        // Same CIK across an amendment (even if the company string differs slightly) — one row.
        var filings = List.of(
                new SpinoffFiling("SPN", "Acme Spinco Inc", "10-12B",
                        LocalDate.of(2026, 5, 1), "u1", "0000123456"),
                new SpinoffFiling("SPN", "Acme Spinco Incorporated", "10-12B/A",
                        LocalDate.of(2026, 5, 20), "u2", "0000123456"),
                new SpinoffFiling("", "Newco Holdings", "10-12B",
                        LocalDate.of(2026, 5, 10), "u3", "0000999999"));

        List<SpinCandidate> out = screener.screen(filings);

        assertThat(out).hasSize(2);
        var acme = out.stream().filter(c -> "0000123456".equals(c.cik())).findFirst().orElseThrow();
        assertThat(acme.filingDate()).isEqualTo("2026-05-20");   // most recent kept
        assertThat(acme.formType()).isEqualTo("10-12B/A");
        var newco = out.stream().filter(c -> "Newco Holdings".equals(c.companyName())).findFirst().orElseThrow();
        assertThat(newco.symbol()).isEmpty();
        assertThat(newco.cik()).isEqualTo("0000999999");
    }

    @Test
    void degradesToCompanyNameWhenCikNull() {
        // No CIK parseable from the URL — dedup falls back to lowercased company name,
        // mirroring COALESCE(cik, lower(company_name)).
        var filings = List.of(
                new SpinoffFiling("SPN", "Acme Spinco Inc", "10-12B",
                        LocalDate.of(2026, 5, 1), "u1", null),
                new SpinoffFiling("SPN", "ACME SPINCO INC", "10-12B/A",
                        LocalDate.of(2026, 5, 20), "u2", null),
                new SpinoffFiling("", "Newco Holdings", "10-12B",
                        LocalDate.of(2026, 5, 10), "u3", null));

        List<SpinCandidate> out = screener.screen(filings);

        assertThat(out).hasSize(2);   // the two Acme rows collapse on lower(company_name)
        var acme = out.stream().filter(c -> c.companyName().equalsIgnoreCase("acme spinco inc"))
                .findFirst().orElseThrow();
        assertThat(acme.filingDate()).isEqualTo("2026-05-20");
        assertThat(acme.formType()).isEqualTo("10-12B/A");
    }

    @Test
    void cikTakesPrecedenceOverName() {
        // Two filings with the same company name but different CIKs are distinct spin-cos.
        var filings = List.of(
                new SpinoffFiling("A", "Generic Holdings", "10-12B",
                        LocalDate.of(2026, 5, 1), "u1", "0000111111"),
                new SpinoffFiling("B", "Generic Holdings", "10-12B",
                        LocalDate.of(2026, 5, 2), "u2", "0000222222"));

        assertThat(screener.screen(filings)).hasSize(2);
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of())).isEmpty();
    }
}
