package de.visterion.dracul.depot;

import java.util.List;

/** Positions for a depot connection with the timestamp Agora reported them as-of. */
public record PositionsSnapshot(List<DepotPosition> positions, String asOf) {
}
