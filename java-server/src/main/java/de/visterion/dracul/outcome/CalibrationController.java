package de.visterion.dracul.outcome;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operator seam: read-only analytics over {@code decision_log} + {@code outcome_log} — Brier
 * calibration (executor + per-hunter), veto precision from counterfactuals, hard-exit latency,
 * whipsaw, stop-basis comparison, slippage. No LLM calls, no writes; sits behind Cloudflare
 * Access like the rest of {@code /api/**} (same as {@link de.visterion.dracul.executor.ExecutorRunController}).
 */
@RestController
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
@RequestMapping("/api/executor")
public class CalibrationController {

    private final OutcomeLogRepository outcomeLogRepository;
    private final CalibrationService calibration;

    public CalibrationController(OutcomeLogRepository outcomeLogRepository, CalibrationService calibration) {
        this.outcomeLogRepository = outcomeLogRepository;
        this.calibration = calibration;
    }

    @GetMapping("/calibration")
    public ResponseEntity<Map<String, Object>> calibration() {
        var executor = calibration.brierResult(outcomeLogRepository.findExecutorBrierPoints());
        List<CalibrationService.HunterBrier> hunters =
                calibration.hunterBrierResults(outcomeLogRepository.findHunterBrierPoints());

        return ResponseEntity.ok(Map.of(
                "executor", executor,
                "hunters", hunters));
    }

    @GetMapping("/behavior")
    public ResponseEntity<Map<String, Object>> behavior() {
        List<CalibrationService.VetoPrecision> vetoPrecision =
                calibration.vetoPrecision(outcomeLogRepository.findVetoRows());
        var latency = calibration.latency(outcomeLogRepository.findHardTriggerLatencySeconds());
        var whipsaw = calibration.whipsaw(outcomeLogRepository.findWhipsawFlags());
        List<CalibrationService.StopBasisStats> stopBasis =
                calibration.stopBasisStats(outcomeLogRepository.findStopBasisRows());
        var slippage = calibration.slippage(outcomeLogRepository.findSlippageValues());

        return ResponseEntity.ok(Map.of(
                "veto_precision", vetoPrecision,
                "caveats", CalibrationService.BEHAVIOR_CAVEATS,
                "hard_exit_latency", latency,
                "whipsaw", whipsaw,
                "stop_basis", stopBasis,
                "slippage", slippage));
    }
}
