package de.visterion.dracul.vistierie;

public record BudgetStatus(
        Long dailyCapMicros,
        Long monthlyCapMicros,
        Integer dailyWarnPercent,
        Integer monthlyWarnPercent,
        long dailyUsageMicros,
        long monthlyUsageMicros,
        boolean dailyWarned,
        boolean monthlyWarned,
        boolean dailyBlocked,
        boolean monthlyBlocked
) {
    public Double dailyCapUsd() {
        return dailyCapMicros == null ? null : dailyCapMicros / 1_000_000.0;
    }

    public Double monthlyCapUsd() {
        return monthlyCapMicros == null ? null : monthlyCapMicros / 1_000_000.0;
    }

    public double dailyUsageUsd() { return dailyUsageMicros / 1_000_000.0; }

    public double monthlyUsageUsd() { return monthlyUsageMicros / 1_000_000.0; }

    public static BudgetStatus empty() {
        return new BudgetStatus(null, null, null, null, 0, 0, false, false, false, false);
    }
}
