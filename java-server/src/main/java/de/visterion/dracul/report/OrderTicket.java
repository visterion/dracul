package de.visterion.dracul.report;

import java.math.BigDecimal;

/** Read-only, informational order ticket. Dracul never places orders.
 *  shares: full position for SELL, floor(shares/3) for TRIM, 0 for HOLD. */
public record OrderTicket(
        String side,            // SELL | TRIM | HOLD
        String symbol,
        double shares,
        BigDecimal limitReference,  // = currentClose (a reference, not an order price)
        BigDecimal stop,            // = activeStop
        BigDecimal target           // = nextTarget2r
) {}
