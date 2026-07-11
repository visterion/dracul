package de.visterion.dracul.outcome;

import de.visterion.dracul.ContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empty-DB sanity check for the Task-10 analytics aggregates: every query must return an empty
 * list (not throw / not 500) when {@code outcome_log}/{@code decision_log} have no matching rows.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "dracul.executor.enabled=true")
class OutcomeLogRepositoryAnalyticsIT {

    @Autowired OutcomeLogRepository repo;
    @Autowired CalibrationService calibration;

    @Test
    void emptyDbYieldsEmptyAggregatesNotErrors() {
        assertThat(repo.findExecutorBrierPoints()).isEmpty();
        assertThat(repo.findHunterBrierPoints()).isEmpty();
        assertThat(repo.findVetoRows()).isEmpty();
        assertThat(repo.findHardTriggerLatencySeconds()).isEmpty();
        assertThat(repo.findWhipsawFlags()).isEmpty();
        assertThat(repo.findStopBasisRows()).isEmpty();
        assertThat(repo.findSlippageValues()).isEmpty();
    }

    @Test
    void emptyDbCalibrationResponseIsInsufficientNotError() {
        var executor = calibration.brierResult(repo.findExecutorBrierPoints());
        assertThat(executor.n()).isEqualTo(0);
        assertThat(executor.insufficient()).isTrue();
        assertThat(executor.buckets()).isEmpty();

        assertThat(calibration.hunterBrierResults(repo.findHunterBrierPoints())).isEmpty();
    }

    @Test
    void emptyDbBehaviorResponseIsZeroedNotError() {
        assertThat(calibration.vetoPrecision(repo.findVetoRows())).isEmpty();

        var latency = calibration.latency(repo.findHardTriggerLatencySeconds());
        assertThat(latency.n()).isEqualTo(0);

        var whipsaw = calibration.whipsaw(repo.findWhipsawFlags());
        assertThat(whipsaw.reentryWithin10d()).isEqualTo(0);
        assertThat(whipsaw.roundtripUnder5d()).isEqualTo(0);

        assertThat(calibration.stopBasisStats(repo.findStopBasisRows())).isEmpty();

        var slippage = calibration.slippage(repo.findSlippageValues());
        assertThat(slippage.n()).isEqualTo(0);
    }
}
