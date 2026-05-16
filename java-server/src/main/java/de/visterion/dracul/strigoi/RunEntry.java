package de.visterion.dracul.strigoi;

import java.util.List;

public record RunEntry(
        String id, String ranAt, int preyCount,
        double costUsd, String model, List<TraceEvent> trace) {}
