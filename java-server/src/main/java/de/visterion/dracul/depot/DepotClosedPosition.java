package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** Broker-authoritative closed position from Agora {@code get_closed_positions} (Saxo).
 *  No timestamp / no broker order id are available from this source today (Agora gap). */
public record DepotClosedPosition(String symbol, BigDecimal openPrice, BigDecimal closePrice,
                                   BigDecimal profitLoss, String clientRef) {
}
