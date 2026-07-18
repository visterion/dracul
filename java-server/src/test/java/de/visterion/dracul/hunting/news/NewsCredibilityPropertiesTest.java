package de.visterion.dracul.hunting.news;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** R1 Major 1: naive constructor binding of an absent prefix would silently yield
 *  0.0/0.0 (everything drops nothing / thresholds dead). These tests pin the
 *  @DefaultValue path and the fail-fast validation contract. Validation is
 *  classpath-driven (spring-boot-starter-validation present, pom.xml:35) — no
 *  explicit ValidationAutoConfiguration import/registration needed. */
class NewsCredibilityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(NewsCredibilityProperties.class)
    static class Config {}

    @Test void prefixEntirelyAbsentBindsDefaultsAndEmptyTable() {
        runner.run(ctx -> {
            var p = ctx.getBean(NewsCredibilityProperties.class);
            assertThat(p.defaultScore()).isEqualTo(0.5);
            assertThat(p.dropBelow()).isEqualTo(0.3);
            assertThat(p.sources()).isEmpty(); // null list tolerated as empty
        });
    }

    @Test void tableEntriesBindInOrder() {
        runner.withPropertyValues(
                "dracul.news.credibility.sources[0].match=reuters.com",
                "dracul.news.credibility.sources[0].score=0.9",
                "dracul.news.credibility.sources[1].match=reddit-stocks",
                "dracul.news.credibility.sources[1].score=0.2")
            .run(ctx -> {
                var p = ctx.getBean(NewsCredibilityProperties.class);
                assertThat(p.sources()).hasSize(2);
                assertThat(p.sources().get(0).match()).isEqualTo("reuters.com");
                assertThat(p.sources().get(0).score()).isEqualTo(0.9);
            });
    }

    @Test void entryScoreOutsideUnitIntervalFailsStartup() {
        runner.withPropertyValues(
                "dracul.news.credibility.sources[0].match=reuters.com",
                "dracul.news.credibility.sources[0].score=1.5")
            .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void defaultScoreOutsideUnitIntervalFailsStartup() {
        runner.withPropertyValues("dracul.news.credibility.default-score=-0.1")
            .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test void dropBelowAboveDefaultScoreFailsStartup() {
        // R2 Minor 4: dropBelow 0.6 would silently hard-drop every unknown source.
        runner.withPropertyValues("dracul.news.credibility.drop-below=0.6")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
