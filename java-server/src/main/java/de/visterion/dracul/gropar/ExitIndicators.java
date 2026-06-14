package de.visterion.dracul.gropar;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic exit indicators for one position. Null + *Available=false when history is too short. */
public record ExitIndicators(
        BigDecimal currentClose,
        BigDecimal gainLossPct,            // vs entry; null if no entry
        BigDecimal atr,        boolean atrAvailable,
        BigDecimal chandelierStop, boolean chandelierBreached,
        BigDecimal ma50,       boolean ma50Available,
        BigDecimal ma200,      boolean ma200Available,
        String maCrossState,               // BULLISH | DEATH_CROSS | NEUTRAL
        BigDecimal high52w,    BigDecimal low52w, boolean window52wAvailable,
        Integer daysHeld,                  // null in v1 (controller may compute later)
        boolean horizonElapsed,
        List<String> firedRules            // CHANDELIER_STOP, DEATH_CROSS, TIME_STOP (PROFIT/STOP added by controller)
) {}
