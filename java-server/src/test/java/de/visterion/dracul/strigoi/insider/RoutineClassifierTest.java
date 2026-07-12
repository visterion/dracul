package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutineClassifierTest {

    private final RoutineClassifier classifier = new RoutineClassifier();

    private static Form4OwnerHistory.Transaction purchase(LocalDate date) {
        return new Form4OwnerHistory.Transaction(date, "P", "A", "4",
                BigDecimal.valueOf(100), null, null, null, null);
    }

    private static Form4OwnerHistory.Transaction tx(LocalDate date, String code) {
        return new Form4OwnerHistory.Transaction(date, code, "A", "4",
                BigDecimal.valueOf(100), null, null, null, null);
    }

    private static final LocalDate REF = LocalDate.of(2026, 6, 15);   // June of the current year

    @Test
    void recurringSameMonthBuysInTwoPriorYearsAreRoutine() {
        var txs = List.of(
                purchase(LocalDate.of(2025, 6, 10)),
                purchase(LocalDate.of(2024, 6, 20)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.ROUTINE);
    }

    @Test
    void plusMinusOneMonthCountsTowardsTheCadence() {
        // May and July are within ±1 month of the June reference
        var txs = List.of(
                purchase(LocalDate.of(2025, 5, 28)),
                purchase(LocalDate.of(2024, 7, 3)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.ROUTINE);
    }

    @Test
    void decemberJanuaryWrapCountsAsWithinOneMonth() {
        LocalDate janRef = LocalDate.of(2026, 1, 15);
        var txs = List.of(
                purchase(LocalDate.of(2024, 12, 20)),
                purchase(LocalDate.of(2025, 2, 5)),
                purchase(janRef));
        assertThat(classifier.classify(txs, janRef, false)).isEqualTo(FilerClassification.ROUTINE);
    }

    @Test
    void onlyOnePriorYearInSameMonthIsNotRoutine() {
        // one prior-year June buy + off-month buys -> track record but no cadence -> opportunistic
        var txs = List.of(
                purchase(LocalDate.of(2025, 6, 10)),
                purchase(LocalDate.of(2024, 2, 10)),
                purchase(LocalDate.of(2023, 11, 10)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.OPPORTUNISTIC);
    }

    @Test
    void fullTrackRecordWithoutCadenceIsOpportunistic() {
        var txs = List.of(
                purchase(LocalDate.of(2025, 1, 10)),
                purchase(LocalDate.of(2024, 9, 10)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.OPPORTUNISTIC);
    }

    @Test
    void thinHistoryIsUnknown() {
        // only two purchases total -> below MIN_CLASSIFIABLE_BUYS -> unknown, not opportunistic
        var txs = List.of(purchase(LocalDate.of(2025, 2, 10)), purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.UNKNOWN);
    }

    @Test
    void truncatedHistoryWithoutCadenceIsUnknown() {
        var txs = List.of(
                purchase(LocalDate.of(2025, 1, 10)),
                purchase(LocalDate.of(2024, 9, 10)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, true)).isEqualTo(FilerClassification.UNKNOWN);
    }

    @Test
    void truncatedHistoryWithCadenceStaysRoutine() {
        // a positive cadence finding survives truncation (older data cannot remove it)
        var txs = List.of(
                purchase(LocalDate.of(2025, 6, 10)),
                purchase(LocalDate.of(2024, 6, 20)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, true)).isEqualTo(FilerClassification.ROUTINE);
    }

    @Test
    void nonPurchaseCodesAreIgnored() {
        // sales and grants (code S / A-award) in the reference month must NOT create a cadence
        var txs = List.of(
                tx(LocalDate.of(2025, 6, 10), "S"),
                tx(LocalDate.of(2024, 6, 20), "A"),
                purchase(REF));
        // only one open-market purchase -> thin -> unknown (never routine off non-P codes)
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.UNKNOWN);
    }

    @Test
    void emptyHistoryIsUnknown() {
        assertThat(classifier.classify(List.of(), REF, false)).isEqualTo(FilerClassification.UNKNOWN);
    }

    @Test
    void currentYearBuysDoNotFormACadence() {
        // several buys THIS year in the reference month, but no PRIOR-year cadence
        var txs = List.of(
                purchase(LocalDate.of(2026, 6, 1)),
                purchase(LocalDate.of(2026, 6, 5)),
                purchase(REF));
        assertThat(classifier.classify(txs, REF, false)).isEqualTo(FilerClassification.OPPORTUNISTIC);
    }
}
