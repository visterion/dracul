package de.visterion.dracul.vistierie;

public record BudgetPatch(
        Long dailyCapMicros,
        Long monthlyCapMicros,
        Integer dailyWarnPercent,
        Integer monthlyWarnPercent
) {
    public static BudgetPatch fromUsd(Double dailyCapUsd, Double monthlyCapUsd,
                                      Integer dailyWarnPct, Integer monthlyWarnPct) {
        return new BudgetPatch(
                dailyCapUsd == null ? null : Math.round(dailyCapUsd * 1_000_000),
                monthlyCapUsd == null ? null : Math.round(monthlyCapUsd * 1_000_000),
                dailyWarnPct, monthlyWarnPct
        );
    }
}
