package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.hunting.DataSourceHealth;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independent sources of incompleteness meet in {@code hunt()}: Agora's own
 *  partial/truncated flags, and the screener's candidate cap. Neither may erase the other —
 *  a run that shows a clean health while half its input is missing is exactly the silent
 *  blindness this branch removes. */
class EchoHealthMergeTest {

    @Test
    void passesHealthyThroughUnchangedWhenNothingWasCut() {
        var agora = DataSourceHealth.healthy("agora");
        assertThat(StrigoiEchoWebhookController.mergeHealth(agora, false)).isSameAs(agora);
    }

    @Test
    void keepsAgorasOwnFlagsWhenOnlyAgoraReportsDegradation() {
        var agora = DataSourceHealth.degraded("agora", "partial: window not fully covered",
                true, false);
        var merged = StrigoiEchoWebhookController.mergeHealth(agora, false);
        assertThat(merged).isSameAs(agora);
        assertThat(merged.partial()).isTrue();
        assertThat(merged.truncated()).isFalse();
    }

    @Test
    void reportsTruncatedWhenOnlyTheScreenerCut() {
        var merged = StrigoiEchoWebhookController.mergeHealth(
                DataSourceHealth.healthy("agora"), true);

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

        var merged = StrigoiEchoWebhookController.mergeHealth(agora, true);

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

        var merged = StrigoiEchoWebhookController.mergeHealth(outage, true);

        assertThat(merged.status()).isEqualTo("unavailable");
        assertThat(merged.isHealthy()).isFalse();
        assertThat(merged).isSameAs(outage);
    }
}
