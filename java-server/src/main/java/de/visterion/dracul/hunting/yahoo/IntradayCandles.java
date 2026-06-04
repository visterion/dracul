package de.visterion.dracul.hunting.yahoo;

import java.math.BigDecimal;
import java.util.List;

public record IntradayCandles(List<BigDecimal> closes, List<Long> volumes) {
    public boolean isEmpty() { return closes.isEmpty(); }
}
