package de.visterion.dracul.depot;

import java.math.BigDecimal;

/** Account snapshot for a depot connection, as returned by {@code get_account}. */
public record DepotAccount(BigDecimal cash, BigDecimal equity, BigDecimal buyingPower,
                            String currency, String status, String asOf) {
}
