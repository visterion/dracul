package de.visterion.dracul.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One daily OHLCV bar, oldest-first ordering when in a list. */
public record OhlcBar(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {}
