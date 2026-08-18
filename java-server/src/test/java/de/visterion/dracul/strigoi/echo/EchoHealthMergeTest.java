package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceHealth;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** THREE independent sources of incompleteness meet in {@code hunt()}: Agora's own
 *  partial/truncated flags, the screener's candidate cap, and the screener's own price-source
 *  outage. None may erase another — a run that shows a clean health while half its input is
 *  missing is exactly the silent blindness this branch removes. */
class EchoHealthMergeTest {

    private static ScreenResult screened(boolean truncated, boolean priceSourceUnavailable) {
        return new ScreenResult(List.of(), truncated, priceSourceUnavailable);
    }

    @Test
    void passesHealthyThroughUnchangedWhenNothingWasCut() {
        var agora = DataSourceHealth.healthy("agora");
        assertThat(StrigoiEchoWebhookController.mergeHealth(agora, screened(false, false))).isSameAs(agora);
    }

    @Test
    void keepsAgorasOwnFlagsWhenOnlyAgoraReportsDegradation() {
        var agora = DataSourceHealth.degraded("agora", "partial: window not fully covered",
                true, false);
        var merged = StrigoiEchoWebhookController.mergeHealth(agora, screened(false, false));
        assertThat(merged).isSameAs(agora);
        assertThat(merged.partial()).isTrue();
        assertThat(merged.truncated()).isFalse();
    }

    @Test
    void reportsTruncatedWhenOnlyTheScreenerCut() {
        var merged = StrigoiEchoWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora"), screened(true, false));

        assertThat(merged.status()).isEqualTo("healthy");
        assertThat(merged.source()).isEqualTo("agora");
        assertThat(merged.partial()).isFalse();
        assertThat(merged.truncated()).isTrue();
        assertThat(merged.detail()).contains("max-candidates");
    }

    @Test
    void orsBothFlagsAndNamesBothCausesWhenAgoraAndTheScreenerDegrade() {
        var agora = DataSourceHealth.degraded("agora",
                "partial: earnings window not fully covered", true, true);

        var merged = StrigoiEchoWebhookController.mergeHealth(agora, screened(true, false));

        assertThat(merged.partial()).isTrue();
        assertThat(merged.truncated()).isTrue();
        assertThat(merged.detail())
                .contains("earnings window not fully covered")
                .contains("max-candidates");
    }

    /** {@link DataSourceHealth#degraded} always yields status "healthy" — merging a real outage
     *  through it would upgrade a total failure into a usable result and void the "if
     *  data_source_health.status is unavailable, return exactly {"prey": []}" clause in the
     *  hunter prompt. */
    @Test
    void neverUpgradesAnUnavailableAgoraToHealthy() {
        var outage = DataSourceHealth.unavailable("agora", "agora: connection refused");

        var merged = StrigoiEchoWebhookController.mergeHealth(outage, screened(true, false));

        assertThat(merged.status()).isEqualTo("unavailable");
        assertThat(merged.isHealthy()).isFalse();
        assertThat(merged).isSameAs(outage);
    }

    @Test
    void reportsPartialWhenOnlyThePriceSourceWentDown() {
        var merged = StrigoiEchoWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora"), screened(false, true));

        assertThat(merged.status()).isEqualTo("healthy");
        assertThat(merged.partial()).isTrue();
        assertThat(merged.truncated()).isFalse();
        assertThat(merged.detail()).contains("price source unavailable");
    }

    @Test
    void orsTruncationAndPriceOutageTogether() {
        var merged = StrigoiEchoWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora"), screened(true, true));

        assertThat(merged.partial()).isTrue();
        assertThat(merged.truncated()).isTrue();
        assertThat(merged.detail())
                .contains("max-candidates")
                .contains("price source unavailable");
    }
}
