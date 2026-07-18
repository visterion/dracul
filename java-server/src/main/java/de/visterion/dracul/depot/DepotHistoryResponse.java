package de.visterion.dracul.depot;

import java.util.List;

public record DepotHistoryResponse(List<DepotHistoryEntry> entries, String error) {
}
