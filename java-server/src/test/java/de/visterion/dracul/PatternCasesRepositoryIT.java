package de.visterion.dracul;

import de.visterion.dracul.pattern.PatternCase;
import de.visterion.dracul.pattern.PatternRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternCasesRepositoryIT {

    static final String PENDING_ID_1 = "c0000000-0000-0000-0000-000000000001";
    static final String PENDING_ID_3 = "c0000000-0000-0000-0000-000000000003";
    static final String ACTIVE_ID    = "c0000000-0000-0000-0000-000000000004";
    static final String UNKNOWN_ID    = "c0000000-0000-0000-0000-0000000000ff";

    @Autowired PatternRepository repo;

    @Test
    void findCasesReturnsSeededRowsForPendingPattern() {
        List<PatternCase> cases = repo.findCases(PENDING_ID_1, "default");
        assertThat(cases).hasSize(12);
        assertThat(cases.stream().filter(PatternCase::supported).count()).isEqualTo(9);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.symbol()).isNotBlank();
            assertThat(c.companyName()).isNotBlank();
            assertThat(c.anomalyType()).isNotBlank();
            assertThat(c.occurredAt()).isNotBlank();
        });
    }

    @Test
    void findCasesOrdersByOccurredAtDesc() {
        List<PatternCase> cases = repo.findCases(PENDING_ID_1, "default");
        var occurred = cases.stream().map(PatternCase::occurredAt).toList();
        assertThat(occurred).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void findCasesForWeakPatternHasZeroSupported() {
        List<PatternCase> cases = repo.findCases(PENDING_ID_3, "default");
        assertThat(cases).hasSize(7);
        assertThat(cases).noneMatch(PatternCase::supported);
    }

    @Test
    void findCasesEmptyForActivePatternWithoutSeed() {
        assertThat(repo.findCases(ACTIVE_ID, "default")).isEmpty();
    }

    @Test
    void findCasesEmptyForUnknownPattern() {
        assertThat(repo.findCases(UNKNOWN_ID, "default")).isEmpty();
    }

    @Test
    void findCasesEmptyForForeignUser() {
        assertThat(repo.findCases(PENDING_ID_1, "someone-else")).isEmpty();
    }
}
