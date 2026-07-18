package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** Broker-authoritative closed position from Agora {@code get_closed_positions} (Saxo).
 *  {@code openTime}/{@code closeTime} are nullable ISO-8601 broker timestamps. {@code clientRef}
 *  echoes Dracul's signal id at placement (join key to executor_position.source_signal_id). */
public record DepotClosedPosition(String symbol, BigDecimal openPrice, BigDecimal closePrice,
                                   BigDecimal profitLoss, String clientRef,
                                   String openTime, String closeTime) {
}
