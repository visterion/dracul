package de.visterion.dracul;

import de.visterion.dracul.verdict.ContributingStrigoiDetail;
import de.visterion.dracul.verdict.VerdictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class VerdictRepositoryIT {

    @Autowired VerdictRepository repo;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM verdicts WHERE symbol IN ('RTST','RTUP','RTMR')").update();
    }

    private String insert(String symbol, String summary, List<String> preyIds) {
        return repo.insertSynthesized(
                symbol, "Test Co", List.of("strigoi-spin", "strigoi-insider"),
                0.88, summary, List.of("SPINOFF", "INSIDER"),
                new BigDecimal("123.4500"), "USD", 0.65, "6m",
                List.of("signal A"), List.of("risk A"),
                List.of(new ContributingStrigoiDetail("strigoi-spin", 0.7, "t1"),
                        new ContributingStrigoiDetail("strigoi-insider", 0.6, "t2")),
                preyIds, "default");
    }

    @Test
    void insertThenFindActiveBySymbol() {
        insert("RTST", "first summary", List.of("p1", "p2"));
        var active = repo.findActiveBySymbol("RTST", "default").orElseThrow();
        assertThat(active.decision()).isNull();
        assertThat(active.contributingPreyIds()).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void updateSynthesizedChangesContentNotDecision() {
        String id = insert("RTUP", "old summary", List.of("p1", "p2"));
        repo.updateDecision(id, "TRACK");
        repo.updateSynthesized(id, "Test Co", List.of("strigoi-spin", "strigoi-insider"),
                0.9, "new summary", List.of("SPINOFF"),
                new BigDecimal("200.0000"), "USD", 0.7, "3m",
                List.of("signal B"), List.of("risk B"),
                List.of(new ContributingStrigoiDetail("strigoi-spin", 0.8, "t3")),
                List.of("p1", "p2", "p3"), "default");
        var detail = repo.findDetailById(id).orElseThrow();
        assertThat(detail.summary()).isEqualTo("new summary");
        assertThat(detail.decision()).isEqualTo("TRACK");
        assertThat(detail.currency()).isEqualTo("USD");
        assertThat(detail.nativeCurrency()).isEqualTo("USD");
        assertThat(detail.nativeCurrentPrice()).isEqualTo(200.0);
        var active = repo.findActiveBySymbol("RTUP", "default").orElseThrow();
        assertThat(active.contributingPreyIds()).containsExactlyInAnyOrder("p1", "p2", "p3");
    }

    @Test
    void findActiveReturnsMostRecent() {
        insert("RTMR", "older", List.of("p1", "p2"));
        insert("RTMR", "newer", List.of("p3", "p4"));
        var active = repo.findActiveBySymbol("RTMR", "default").orElseThrow();
        assertThat(active.contributingPreyIds()).containsExactlyInAnyOrder("p3", "p4");
    }

    @Test
    void contributingPreyIdsById_returnsIds() {
        String id = insert("RTST", "summary", List.of("prey-1", "prey-2"));
        assertThat(repo.contributingPreyIdsById(id)).containsExactly("prey-1", "prey-2");
        assertThat(repo.contributingPreyIdsById("00000000-0000-0000-0000-000000000000")).isEmpty();
    }

    @Test
    void contributingPreyIdsById_unknownFormatReturnsEmpty() {
        assertThat(repo.contributingPreyIdsById("not-a-uuid")).isEmpty();
    }
}
