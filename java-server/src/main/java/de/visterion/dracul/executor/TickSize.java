package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Rundung von Orderpreisen auf das Handelsraster.
 *
 * <p>Reine Funktionen, keine Abhaengigkeiten, {@code null}-tolerant. Die Richtungen sind
 * so gewaehlt, dass die Rundung das Risiko nie vergroessert: der Entry bewegt sich vom
 * Fill weg, Stop und Target bewegen sich zum Entry hin.
 *
 * <p><b>Raster ist durchgaengig 0,01 — auch unter 1 $.</b> Ein feineres Raster (SEC Rule
 * 612 sieht unter 1 $ 0,0001 vor) waere die riskantere Wahl: 0,01 ist unter jedem
 * denkbaren Sub-Penny-Schema gueltig, ein 0,0001-Raster wuerde dagegen eine Ablehnung
 * ERZEUGEN, falls Saxo fuer ein Instrument 0,01 verlangt.
 */
public final class TickSize {

    private static final BigDecimal TICK = new BigDecimal("0.01");

    private TickSize() { }

    public static BigDecimal tickFor(BigDecimal price) {
        return price == null ? null : TICK;
    }

    /**
     * Entry: BUY ab-, SELL aufrunden — weg vom Fill.
     *
     * <p>Rounding never turns a positive price into a non-positive one. If the input is
     * already zero or negative, it passes through unchanged; callers must veto non-positive
     * prices before sizing (e.g., in {@code PositionSizer.java:35}).
     */
    public static BigDecimal roundEntry(String side, BigDecimal price) {
        BigDecimal r = round(side, price, RoundingMode.FLOOR, RoundingMode.CEILING);
        // Nie 0 liefern: PositionSizer:35 teilt ohne Positivitaets-Guard durch den Preis.
        // Der Eingabewert laeuft dann wie heute in die bestehenden Vetos.
        if (r != null && r.signum() <= 0) return price;
        return r;
    }

    /**
     * Stop: BUY auf-, SELL abrunden — hin zum Entry. For initial protective stops only;
     * trailing stops in {@code StopRatchetService} use the opposite direction (away from
     * entry) intentionally to avoid premature stop-outs.
     */
    public static BigDecimal roundStop(String side, BigDecimal price) {
        return round(side, price, RoundingMode.CEILING, RoundingMode.FLOOR);
    }

    /** Target: BUY ab-, SELL aufrunden — hin zum Entry. */
    public static BigDecimal roundTarget(String side, BigDecimal price) {
        return round(side, price, RoundingMode.FLOOR, RoundingMode.CEILING);
    }

    private static BigDecimal round(String side, BigDecimal price,
                                    RoundingMode buyMode, RoundingMode sellMode) {
        if (price == null) return null;
        String s = side == null ? "" : side.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "BUY"  -> price.setScale(TICK.scale(), buyMode);
            case "SELL" -> price.setScale(TICK.scale(), sellMode);
            default -> throw new IllegalArgumentException("unknown side: " + side);
        };
    }
}
