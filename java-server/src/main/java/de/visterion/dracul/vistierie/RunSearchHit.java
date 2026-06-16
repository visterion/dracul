package de.visterion.dracul.vistierie;

import java.time.Instant;

public record RunSearchHit(String runId, String agent, String status, boolean hasError,
                           Instant startedAt, double rank, String snippet) {}
