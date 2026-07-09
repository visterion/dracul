package de.visterion.dracul.executor.broker;

/** Result of placing a bracket: the broker-assigned ids for the entry and its two legs. */
public record PlacedBracket(String bracketId, String stopLegId, String takeProfitLegId, String clientRef,
        OrderStatus status) {
}
