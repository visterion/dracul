package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.agora.Form4OwnerHistory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic routine/opportunistic classifier for a single insider's open-market purchase
 * history (Cohen, Malloy &amp; Pomorski 2012). Operates only on transaction code {@code "P"}
 * (open-market purchases) — grants/awards and option exercises are excluded, since those are
 * inherently calendar-driven compensation events and would pollute the "musterabweichend" test.
 *
 * <h3>Rule (conservative and testable)</h3>
 * Let the <em>reference</em> be the calendar month/year of the insider's current cluster
 * purchase.
 * <ol>
 *   <li><b>ROUTINE</b> when at least {@link #MIN_MATCHING_PRIOR_YEARS} distinct <em>prior</em>
 *       calendar years each contain a purchase in the same calendar month as the reference,
 *       within &plusmn;1 month (December/January wrap included). A recurring cadence is a
 *       <em>positive</em> finding: it is returned even when the history is {@code truncated},
 *       because more (older) data cannot remove a cadence that is already present.</li>
 *   <li><b>UNKNOWN</b> otherwise, when the basis is too thin to trust the ABSENCE of a cadence:
 *       the history is {@code truncated} (incomplete) OR carries fewer than
 *       {@link #MIN_CLASSIFIABLE_BUYS} open-market purchases. A first-time / sparse buyer is
 *       explicitly not labelled opportunistic — that would score noise as signal.</li>
 *   <li><b>OPPORTUNISTIC</b> otherwise: a complete-enough history with a real track record and
 *       no recurring calendar cadence.</li>
 * </ol>
 */
@Component
public class RoutineClassifier {

    /** Distinct prior years needing a same-month purchase for the cadence to count as routine. */
    static final int MIN_MATCHING_PRIOR_YEARS = 2;
    /** Minimum open-market purchases in the history before ABSENCE of a cadence is trusted. */
    static final int MIN_CLASSIFIABLE_BUYS = 3;

    /**
     * Classify one owner's open-market purchase history against a reference purchase date.
     *
     * @param ownerTransactions the owner's full multi-year transaction list (any codes; only
     *                          open-market purchases, code {@code "P"}, are considered)
     * @param referenceDate     the current cluster purchase date whose month anchors the cadence test
     * @param truncated         whether the source owner history was incomplete
     */
    public FilerClassification classify(List<Form4OwnerHistory.Transaction> ownerTransactions,
                                        LocalDate referenceDate, boolean truncated) {
        if (ownerTransactions == null || referenceDate == null) return FilerClassification.UNKNOWN;

        List<Form4OwnerHistory.Transaction> purchases = ownerTransactions.stream()
                .filter(t -> t.transactionDate() != null)
                .filter(t -> "P".equalsIgnoreCase(t.code()))
                .toList();

        int refYear = referenceDate.getYear();
        int refMonth = referenceDate.getMonthValue();

        Set<Integer> priorYearsSameMonth = new HashSet<>();
        for (Form4OwnerHistory.Transaction t : purchases) {
            int y = t.transactionDate().getYear();
            if (y >= refYear) continue;                       // only strictly prior years
            if (withinOneMonth(t.transactionDate().getMonthValue(), refMonth)) {
                priorYearsSameMonth.add(y);
            }
        }

        // Positive finding wins, even on a truncated history.
        if (priorYearsSameMonth.size() >= MIN_MATCHING_PRIOR_YEARS) {
            return FilerClassification.ROUTINE;
        }
        // Absence of a cadence is only trusted on a complete-enough, non-trivial history.
        if (truncated || purchases.size() < MIN_CLASSIFIABLE_BUYS) {
            return FilerClassification.UNKNOWN;
        }
        return FilerClassification.OPPORTUNISTIC;
    }

    /** Same calendar month &plusmn;1, wrapping Dec(12)&harr;Jan(1). */
    static boolean withinOneMonth(int month, int reference) {
        int diff = Math.abs(month - reference);
        return diff <= 1 || diff == 11;
    }
}
