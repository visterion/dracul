package de.visterion.dracul.vistierie;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards Spring's ability to instantiate {@link HttpVistierieClient}. The class has two
 * constructors (the {@code @Value} production one and a package-private test one), so Spring
 * needs an unambiguous autowire candidate. Without {@code @Autowired} on the production
 * constructor the container falls back to a non-existent no-arg constructor and the whole
 * application context fails to start. None of the {@code @SpringBootTest} ITs catch this
 * because they all run the {@code dev} profile (MockVistierieClient), so the real client is
 * never instantiated by Spring there.
 */
class HttpVistierieClientWiringTest {

    @Test
    void springCanInstantiateHttpVistierieClientFromItsConstructor() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withBean(HttpVistierieClient.class)
                .withPropertyValues(
                        "dracul.vistierie.url=http://vistierie:8090",
                        "dracul.vistierie.tenant-token=t",
                        "dracul.vistierie.admin-token=a")
                .run(ctx -> assertThat(ctx).hasSingleBean(HttpVistierieClient.class));
    }
}
