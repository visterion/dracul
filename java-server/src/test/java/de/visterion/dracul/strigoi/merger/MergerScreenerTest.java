package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.edgar.MergerFiling;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergerScreenerTest {

    private final MergerScreener screener = new MergerScreener();

    @Test
    void dedupesSameTargetToMostRecentFiling() {
        var filings = List.of(
                new MergerFiling("TGT", "Target Corp", "PREM14A", LocalDate.of(2026, 5, 1), "u1"),
                new MergerFiling("TGT", "Target Corp", "DEFM14A", LocalDate.of(2026, 5, 20), "u2"),
                new MergerFiling("AQD", "Acquired Inc", "SC TO-T", LocalDate.of(2026, 5, 10), "u3"));

        List<MergerCandidate> out = screener.screen(filings);

        assertThat(out).hasSize(2);
        var tgt = out.stream().filter(c -> "TGT".equals(c.symbol())).findFirst().orElseThrow();
        assertThat(tgt.filingDate()).isEqualTo("2026-05-20");   // most recent kept
        assertThat(tgt.formType()).isEqualTo("DEFM14A");
        var aqd = out.stream().filter(c -> "AQD".equals(c.symbol())).findFirst().orElseThrow();
        assertThat(aqd.formType()).isEqualTo("SC TO-T");
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of())).isEmpty();
    }
}
