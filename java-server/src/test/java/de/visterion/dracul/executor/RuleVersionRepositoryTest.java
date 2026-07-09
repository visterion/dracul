package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class RuleVersionRepositoryTest {

    @Autowired RuleVersionRepository repo;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    @Test
    void upsertFindExistsAndUpdate() {
        String version = "exec-test-" + UUID.randomUUID();
        var params = mapper.readTree("{\"chandelier_mult\":3.0}");

        assertThat(repo.exists(version)).isFalse();

        repo.upsert(new RuleVersion(version, LocalDate.now().toString(), "initial", "hash-1", params));
        assertThat(repo.exists(version)).isTrue();

        var found = repo.find(version);
        assertThat(found).isNotNull();
        assertThat(found.ruleVersion()).isEqualTo(version);
        assertThat(found.changes()).isEqualTo("initial");
        assertThat(found.params().path("chandelier_mult").asDouble()).isEqualTo(3.0);

        repo.upsert(new RuleVersion(version, LocalDate.now().toString(), "updated", "hash-2", params));
        var updated = repo.find(version);
        assertThat(updated.changes()).isEqualTo("updated");
        assertThat(updated.promptHash()).isEqualTo("hash-2");
    }

    @Test
    void findMissingReturnsNull() {
        assertThat(repo.find("no-such-version-" + UUID.randomUUID())).isNull();
    }
}
