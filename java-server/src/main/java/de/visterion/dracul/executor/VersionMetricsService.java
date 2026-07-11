package de.visterion.dracul.executor;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Version-scoped outcome metrics (Task 5 / item 23): groups completed executor outcomes by
 * {@code (source_agent, agent_version, rule_version)} and flags a group
 * {@code insufficientSample} until it has both run for at least {@link #MIN_SPAN_DAYS} days
 * ({@code first_at} to {@code last_at}) AND accumulated at least {@link #MIN_DECISIONS}
 * decisions — "2 weeks or 20 decisions, whichever is later" means both thresholds must hold.
 */
@Service
public class VersionMetricsService {

    static final int MIN_SPAN_DAYS = 14;
    static final int MIN_DECISIONS = 20;

    private final VersionMetricsRepository repository;

    public VersionMetricsService(VersionMetricsRepository repository) {
        this.repository = repository;
    }

    public List<VersionMetrics> metrics() {
        return repository.findGroupedByVersion().stream().map(VersionMetricsService::toMetrics).toList();
    }

    static VersionMetrics toMetrics(VersionMetricsRepository.Row row) {
        boolean sufficient = Duration.between(row.firstAt(), row.lastAt()).toDays() >= MIN_SPAN_DAYS
                && row.decisions() >= MIN_DECISIONS;
        return new VersionMetrics(row.agent(), row.agentVersion(), row.ruleVersion(), row.decisions(),
                row.firstAt(), row.lastAt(), row.avgReturn(), row.hitRate(), !sufficient);
    }

    /** One version-scoped outcome-metrics group. {@code insufficientSample} is true unless the
     *  group spans at least {@value #MIN_SPAN_DAYS} days AND has at least {@value #MIN_DECISIONS}
     *  decisions. */
    public record VersionMetrics(
            String agent,
            @JsonProperty("agent_version") String agentVersion,
            @JsonProperty("rule_version") String ruleVersion,
            int decisions,
            @JsonProperty("first_at") Instant firstAt,
            @JsonProperty("last_at") Instant lastAt,
            @JsonProperty("avg_return") Double avgReturn,
            @JsonProperty("hit_rate") Double hitRate,
            @JsonProperty("insufficient_sample") boolean insufficientSample) {
    }
}
