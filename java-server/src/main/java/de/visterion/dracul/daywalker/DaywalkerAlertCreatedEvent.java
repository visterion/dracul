package de.visterion.dracul.daywalker;

/** Published once per eligible owner after that owner's Daywalker alert row is persisted, so live
 *  consumers (SSE) can deliver the toast to exactly that owner. */
public record DaywalkerAlertCreatedEvent(String owner, String symbol, String triggerType,
                                         String severity, String thesis) {}
