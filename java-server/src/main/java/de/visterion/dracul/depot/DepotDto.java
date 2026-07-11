package de.visterion.dracul.depot;

import java.math.BigDecimal;
import java.util.List;

/**
 * GUI-facing view of one depot connection: account, positions (quote-enriched), orders, and
 * derived aggregates. {@code error} is non-null (and {@code account}/{@code aggregates}/
 * {@code positions}/{@code orders} are null) when the per-connection Agora calls failed.
 */
public record DepotDto(String id, String provider, String environment, String status,
        String probedAt, String error, DepotAccount account, DepotAggregates aggregates,
        List<DepotPositionDto> positions, List<DepotOrder> orders, String asOf) {
}
