package de.visterion.dracul.research;

import java.time.Instant;

/**
 * Links a persisted research entity (currently only {@code kind = "prey"}) to the HiveMem
 * memory cell holding its thesis, so a later realized outcome can be written back to the
 * correct cell. {@code refId} is a String because ids are heterogeneous across kinds, even
 * though only {@code "prey"} is written in v1 (Prey.id() is a String/UUID-as-text).
 */
public record ResearchMemoryLink(Long id, String kind, String refId, String symbol,
        String cellId, Instant createdAt, boolean outcomeWritten) {}
