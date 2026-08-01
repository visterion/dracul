package de.visterion.dracul.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class TickSizeTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // Die drei Werte stammen woertlich aus decision_log: sie haben in Produktion
    // BROKER_ERROR ausgeloest (PriceNotInTickSizeIncrements).
    @ParameterizedTest
    @CsvSource({
            "BUY,  96.415,  96.41",
            "SELL, 96.415,  96.42",
            "BUY,  151.345, 151.34",
            "SELL, 151.345, 151.35",
            "BUY,  70.505,  70.50",
            "SELL, 70.505,  70.51",
    })
    void roundEntry_movesAwayFromTheFill(String side, String in, String want) {
        assertThat(TickSize.roundEntry(side, bd(in))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd(want));
    }

    // Stop rundet GEGENLAEUFIG zum Entry: naeher an den Entry, also Risiko pro Aktie
    // tendenziell kleiner. Richtung darf niemals mit roundEntry verwechselt werden.
    @ParameterizedTest
    @CsvSource({
            "BUY,  75.855, 75.86",
            "SELL, 75.855, 75.85",
    })
    void roundStop_movesTowardTheEntry(String side, String in, String want) {
        assertThat(TickSize.roundStop(side, bd(in))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd(want));
    }

    @ParameterizedTest
    @CsvSource({
            "BUY,  96.418, 96.41",
            "SELL, 96.412, 96.42",
    })
    void roundTarget_movesTowardTheEntry(String side, String in, String want) {
        assertThat(TickSize.roundTarget(side, bd(in))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd(want));
    }

    // compareTo, NICHT equals: 96.41 und 96.4100 sind gleich, aber nicht equals-gleich.
    @Test
    void isIdempotentForOnTickValues() {
        for (String s : new String[]{"96.41", "0.01", "1.00", "410.00"}) {
            assertThat(TickSize.roundEntry("BUY", bd(s))).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(bd(s));
            assertThat(TickSize.roundStop("SELL", bd(s))).usingComparator(BigDecimal::compareTo)
                    .isEqualTo(bd(s));
        }
    }

    // DER Sicherheitsfall: PositionSizer.java:35 teilt ohne Positivitaets-Guard durch den
    // Preis. Ein auf 0 gerundeter Sub-Cent-Preis riss den Sizer in eine
    // ArithmeticException und damit in einen HTTP-500 OHNE decision_log-Zeile — heute
    // laeuft derselbe Fall sauber ins LIQUIDITY-Veto.
    @Test
    void roundEntry_doesNotRoundASubCentPriceDownToZero() {
        assertThat(TickSize.roundEntry("BUY", bd("0.004"))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd("0.004"));
        assertThat(TickSize.roundEntry("BUY", bd("0.004"))).isGreaterThan(BigDecimal.ZERO);
    }

    // Non-positive input passes through unchanged; callers must veto such prices before sizing.
    @Test
    void roundEntry_passesNonPositivePricesThrough() {
        assertThat(TickSize.roundEntry("BUY", BigDecimal.ZERO)).usingComparator(BigDecimal::compareTo)
                .isEqualTo(BigDecimal.ZERO);
        assertThat(TickSize.roundEntry("BUY", bd("-5.00"))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd("-5.00"));
    }

    @Test
    void gridIsOneCentEverywhere() {
        // Auch unter 1 $ kein Sub-Penny-Raster: 0,01 ist unter jedem denkbaren Schema
        // gueltig, ein feineres Raster wuerde eine Ablehnung ERZEUGEN.
        assertThat(TickSize.tickFor(bd("0.50"))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd("0.01"));
        assertThat(TickSize.tickFor(bd("410.00"))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd("0.01"));
    }

    @Test
    void nullInNullOut() {
        assertThat(TickSize.roundEntry("BUY", null)).isNull();
        assertThat(TickSize.roundStop("SELL", null)).isNull();
        assertThat(TickSize.roundTarget("BUY", null)).isNull();
        assertThat(TickSize.tickFor(null)).isNull();
    }

    // Nicht raten: eine unbekannte Seite ist ein Programmierfehler, kein Default.
    @Test
    void unknownSideIsRejected() {
        assertThatThrownBy(() -> TickSize.roundEntry("HOLD", bd("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TickSize.roundEntry(null, bd("10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // toUpperCase allows lowercase sides.
    @ParameterizedTest
    @CsvSource({
            "buy,  96.415,  96.41",
            "sell, 96.415,  96.42",
            "Buy,  151.345, 151.34",
            "SELL, 151.345, 151.35",
    })
    void roundEntry_acceptsLowercaseAndMixedCaseSides(String side, String in, String want) {
        assertThat(TickSize.roundEntry(side, bd(in))).usingComparator(BigDecimal::compareTo)
                .isEqualTo(bd(want));
    }
}
