package de.visterion.dracul.daywalker;

/** Published after a Daywalker alert is persisted, so live consumers (SSE) can react. */
public record DaywalkerAlertCreatedEvent(String symbol, String triggerType,
                                         String severity, String thesis) {}
