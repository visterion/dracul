package de.visterion.dracul.chronicle;

public record DaywalkerAlert(String id, String symbol, String description,
                              String severity, String triggeredAt) {}
