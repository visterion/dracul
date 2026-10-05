package de.visterion.dracul.strigoi.momentum;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class MomentumSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTheCompletionView() {
        var stored = new MomentumRepository.StoredSnapshot("run-1", null, "2026-10", true, "partial",
                mapper.readTree("""
                        {"llm": {"ranking": {"rebalance_due": true}},
                         "ranked_count": 3,
                         "ranked_all": [{"symbol": "SYNA", "rank": 1, "momentum_pct": 50.1},
                                        {"symbol": "SYNB", "rank": 2, "momentum_pct": 40.2},
                                        {"symbol": "SYNC", "rank": 3, "momentum_pct": 30.3}],
                         "universe": ["SYNA", "SYNB", "SYNC", "SYND"],
                         "unranked": {"SYND": "missing"},
                         "offered": [{"rank": 1, "symbol": "SYNA", "company_name": "Synthetic A Corp",
                                      "momentum_12_1_pct": 50.1, "return_1m_pct": 2.5}]}
                        """));

        MomentumSnapshot s = MomentumSnapshot.of(stored);

        assertThat(s.month()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(s.usable()).isTrue();
        assertThat(s.rankedCount()).isEqualTo(3);
        assertThat(s.rankOf("synb")).isEqualTo(2);
        assertThat(s.rankOf("SYND")).isNull();
        assertThat(s.inUniverse("SYND")).isTrue();
        assertThat(s.inUniverse("SYNZ")).isFalse();
        assertThat(s.unranked()).containsEntry("SYND", "missing");
        assertThat(s.offered()).singleElement().satisfies(o -> {
            assertThat(o.rank()).isEqualTo(1);
            assertThat(o.companyName()).isEqualTo("Synthetic A Corp");
            assertThat(o.momentumPct()).isEqualByComparingTo("50.1");
        });
        assertThat(MomentumSnapshot.unrankedSymbols(stored.payload())).containsExactly("SYND");
    }

    @Test
    void notDueAndUnavailableAreNotUsable() {
        var notDue = new MomentumRepository.StoredSnapshot("r", null, null, false, "not_due",
                mapper.readTree("{\"llm\": {}}"));
        var unavailable = new MomentumRepository.StoredSnapshot("r", null, "2026-10", true,
                "unavailable", mapper.readTree("{\"llm\": {}}"));
        assertThat(MomentumSnapshot.of(notDue).usable()).isFalse();
        assertThat(MomentumSnapshot.of(unavailable).usable()).isFalse();
    }
}
