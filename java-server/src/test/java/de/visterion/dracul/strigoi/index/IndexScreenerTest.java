package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.agora.Sp500Constituent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexScreenerTest {

    private final IndexScreener screener = new IndexScreener();

    @Test
    void keepsRecentAdditionsAndDropsOld() {
        var rows = List.of(
                new Sp500Constituent("NEWO", "NewCo", LocalDate.now().minusDays(5)),
                new Sp500Constituent("OLDX", "OldCo", LocalDate.now().minusDays(400)));

        List<IndexCandidate> out = screener.screen(rows, 30);

        assertThat(out).extracting(IndexCandidate::symbol).containsExactly("NEWO");
        assertThat(out.get(0).dateAdded()).isEqualTo(LocalDate.now().minusDays(5).toString());
    }

    @Test
    void dedupesSymbolToMostRecentDate() {
        var rows = List.of(
                new Sp500Constituent("DUP", "Dup Inc", LocalDate.now().minusDays(20)),
                new Sp500Constituent("DUP", "Dup Inc", LocalDate.now().minusDays(3)));

        List<IndexCandidate> out = screener.screen(rows, 30);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).dateAdded()).isEqualTo(LocalDate.now().minusDays(3).toString());
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertThat(screener.screen(List.of(), 30)).isEmpty();
    }
}
