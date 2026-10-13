package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spec §2.7, §3.2, §3.3 for ONE event and ONE horizon. Pure: everything it needs is passed in.
 * <ul>
 *   <li>Entry = open of the bar {@link EntryRule} picks (close if the open is missing).</li>
 *   <li>Exit = close of bar {@code entry + h} (plan ruling R8).</li>
 *   <li>Delisted (plan ruling R7): once the EUNL horizon date exists, an instrument without a bar
 *       for ≥ 10 EUNL trading days exits at its last bar — at the merger offer when one is known
 *       in the instrument's currency, else at the last close.</li>
 *   <li>NO_ENTRY: no instrument bar after T while EUNL has ≥ 10 trading days after T.</li>
 *   <li>Benchmark: EUNL close on the last day before the entry date → last day ≤ exit date.</li>
 *   <li>EUR: {@code (1 + r_local) × fx_exit / fx_entry − 1}; fx_entry = close on the last FX day
 *       BEFORE the entry date, fx_exit = last FX close ≤ exit date.</li>
 * </ul>
 * Anything missing is {@link Pending} — never a guessed number.
 */
public final class OutcomeCalculator {

    public static final List<Integer> HORIZONS = List.of(20, 60);
    public static final int NO_ENTRY_EUNL_DAYS = 10;
    public static final int DELISTING_EUNL_DAYS = 10;

    private static final MathContext MC = MathContext.DECIMAL64;

    public sealed interface Result permits Pending, Complete {}

    public record Pending(String reason) implements Result {}

    public record Complete(BenchmarkOutcome row) implements Result {}

    private OutcomeCalculator() {
    }

    public static Result compute(MeasureEvent e, int horizon, PriceSeries instrument, String currency,
                                 PriceSeries eunl, TradingCalendar eunlCal, PriceSeries fx) {
        List<OhlcBar> bars = instrument.bars();
        int entryIdx = EntryRule.entryIndex(bars, e.refAt(), currency);
        if (entryIdx < 0) {
            LocalDate eligible = EntryRule.firstEligibleDate(e.refAt(), currency);
            if (eunlCal.countAfter(eligible.minusDays(1)) >= NO_ENTRY_EUNL_DAYS) {
                return new Complete(BenchmarkOutcome.noEntry(e, horizon, currency));
            }
            return new Pending("no instrument bar after the reference yet");
        }
        OhlcBar entry = bars.get(entryIdx);
        BigDecimal entryPrice = entry.open() != null && entry.open().signum() > 0 ? entry.open() : entry.close();

        OhlcBar exitBar;
        BigDecimal exitPrice;
        ExitKind exitKind;
        int exitIdx = entryIdx + horizon;
        if (exitIdx < bars.size()) {
            exitBar = bars.get(exitIdx);
            exitPrice = exitBar.close();
            exitKind = ExitKind.NORMAL;
        } else {
            Optional<LocalDate> horizonDate = eunlCal.plus(entry.date(), horizon);
            if (horizonDate.isEmpty()) return new Pending("horizon not reached on the EUNL calendar");
            OhlcBar last = instrument.lastBar();
            if (eunlCal.countAfter(last.date()) < DELISTING_EUNL_DAYS) {
                return new Pending("not enough instrument bars yet");
            }
            exitBar = last;
            Offer offer = e.offer();
            if (offer != null && offer.price() != null && offer.price().signum() > 0
                    && (offer.currency() == null || offer.currency().equals(currency))) {
                exitPrice = offer.price();
                exitKind = ExitKind.DELISTED_OFFER;
            } else {
                exitPrice = last.close();
                exitKind = ExitKind.DELISTED_LAST;
            }
        }
        LocalDate exitDate = exitBar.date();

        Optional<OhlcBar> benchStart = eunl.lastBefore(entry.date());
        Optional<OhlcBar> benchEnd = eunl.lastOnOrBefore(exitDate);
        if (benchStart.isEmpty() || benchEnd.isEmpty()) return new Pending("benchmark bars missing");

        BigDecimal fxEntry = BigDecimal.ONE;
        BigDecimal fxExit = BigDecimal.ONE;
        if (!CurrencyResolver.EUR.equals(currency)) {
            if (fx == null) return new Pending("fx series missing");
            Optional<OhlcBar> fe = fx.lastBefore(entry.date());
            Optional<OhlcBar> fo = fx.lastOnOrBefore(exitDate);
            if (fe.isEmpty() || fo.isEmpty()) return new Pending("fx bars missing");
            fxEntry = fe.get().close();
            fxExit = fo.get().close();
        }

        BigDecimal rLocal = exitPrice.divide(entryPrice, MC).subtract(BigDecimal.ONE);
        BigDecimal rEur = BigDecimal.ONE.add(rLocal).multiply(fxExit, MC).divide(fxEntry, MC).subtract(BigDecimal.ONE);
        BigDecimal rBench = benchEnd.get().close().divide(benchStart.get().close(), MC).subtract(BigDecimal.ONE);
        BigDecimal returnEur = ret(rEur);
        BigDecimal benchmark = ret(rBench);

        return new Complete(new BenchmarkOutcome(e.kind(), e.agent(), e.symbol(), e.eventStart(), e.refDate(),
                e.refAt(), e.picked(), horizon, entry.date(), price(entryPrice), exitDate, price(exitPrice),
                currency, fx(fxEntry), fx(fxExit), returnEur, benchmark, returnEur.subtract(benchmark), exitKind));
    }

    private static BigDecimal ret(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal fx(BigDecimal v) {
        return v.setScale(8, RoundingMode.HALF_UP);
    }
}
