package de.visterion.dracul.strigoi;

import de.visterion.dracul.prey.Prey;
import java.util.List;

public record StrigoiDetail(
        String name, String anomalyType, String description, String reference,
        String state, String lastRunAt, String nextRunAt,
        int huntsThisMonth, int scheduledHuntsThisMonth, double avgPreyPerHunt,
        double hitRate90d, int hitRateNumerator, int hitRateDenominator,
        List<RunEntry> recentRuns, List<Prey> recentPrey,
        StrigoiConfiguration configuration, List<WeeklyPerformance> weeklyPerformance) {}
