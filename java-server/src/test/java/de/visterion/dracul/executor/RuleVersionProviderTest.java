package de.visterion.dracul.executor;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.executor.enabled=true",
        "dracul.executor.rule-version=exec-test-1"
})
class RuleVersionProviderTest {

    @Autowired RuleVersionProvider provider;
    @Autowired RuleVersionRepository repo;

    @Test
    void seedsActiveVersionOnStartup() {
        assertThat(provider.active()).isEqualTo("exec-test-1");
        assertThat(repo.exists("exec-test-1")).isTrue();

        var v = repo.find("exec-test-1");
        assertThat(v).isNotNull();
        assertThat(v.params().path("chandelier_mult").asDouble()).isEqualTo(3.0);
        assertThat(v.params().path("confidence_min").asDouble()).isEqualTo(0.65);
        assertThat(v.params().path("trim_fractions").asString()).isEqualTo("0.33,0.5,1.0");
        assertThat(v.params().path("entry_gtd_days").asInt()).isEqualTo(2);
        assertThat(v.params().path("kill_criteria_hard").asString()).isEqualTo("price-level only");
    }
}
