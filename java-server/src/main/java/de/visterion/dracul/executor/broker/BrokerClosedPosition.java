package de.visterion.dracul.executor.broker;

import java.math.BigDecimal;

/** Mapped result of Agora's {@code get_closed_positions} tool — the realized open/close
 *  fills of a closed broker position. {@code clientRef} may be absent (null) for positions
 *  opened before clientRef tracking existed. */
public record BrokerClosedPosition(String symbol, BigDecimal openPrice, BigDecimal closePrice,
                                    BigDecimal profitLoss, String clientRef) {}
