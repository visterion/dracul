package de.visterion.dracul.pattern;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises findAcceptedByStrigoi/findAllAccepted against the real (testcontainer) DB,
 *  seeded via V2__seed.sql (17 ACTIVE/PENDING patterns). We insert additional
 *  unique-statement rows scoped to a throwaway strigoi name so assertions don't depend
 *  on seed-data churn. */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class PatternRepositoryTest {

    @Autowired PatternRepository repo;

    @Test
    void findAcceptedByStrigoiReturnsOnlyActiveStatementsForThatStrigoiOrAll() {
        String strigoi = "test-strigoi-" + UUID.randomUUID();
        String activeStatement = "Active lesson for " + strigoi;
        String pendingStatement = "Pending lesson for " + strigoi;
        String allScopeStatement = "All-scope lesson " + UUID.randomUUID();

        insertAndSetStatus(strigoi, activeStatement, "ACTIVE");
        insertAndSetStatus(strigoi, pendingStatement, "PENDING");
        insertAndSetStatus("all", allScopeStatement, "ACTIVE");

        var result = repo.findAcceptedByStrigoi(strigoi);

        assertThat(result).contains(activeStatement, allScopeStatement);
        assertThat(result).doesNotContain(pendingStatement);
    }

    @Test
    void findAcceptedByStrigoiExcludesOtherStrigoiSpecificPatterns() {
        String mine = "test-strigoi-mine-" + UUID.randomUUID();
        String other = "test-strigoi-other-" + UUID.randomUUID();
        String otherStatement = "Lesson only for " + other;

        insertAndSetStatus(other, otherStatement, "ACTIVE");

        var result = repo.findAcceptedByStrigoi(mine);

        assertThat(result).doesNotContain(otherStatement);
    }

    @Test
    void findAcceptedByStrigoiReturnsEmptyForUnknownStrigoi() {
        var result = repo.findAcceptedByStrigoi("test-strigoi-with-no-patterns-" + UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    void findAllAcceptedIncludesActivePatternsAcrossStrigoi() {
        String strigoi = "test-strigoi-all-" + UUID.randomUUID();
        String statement = "Cross-hunter lesson " + strigoi;
        insertAndSetStatus(strigoi, statement, "ACTIVE");

        var result = repo.findAllAccepted();

        assertThat(result).contains(statement);
    }

    private void insertAndSetStatus(String strigoi, String statement, String status) {
        repo.insertProposal("default", strigoi, statement, 1);
        if (!"PENDING".equals(status)) {
            var pattern = repo.findAllByUser("default").stream()
                    .filter(p -> statement.equals(p.statement()))
                    .findFirst()
                    .orElseThrow();
            repo.updateStatus(pattern.id(), "default", status);
        }
    }
}
