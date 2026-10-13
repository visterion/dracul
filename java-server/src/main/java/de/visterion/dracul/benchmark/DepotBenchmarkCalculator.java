package de.visterion.dracul.benchmark;

import de.visterion.dracul.marketdata.OhlcBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Spec §4.2, per source Strigoi: capital-weighted EUR return of its positions vs EUNL over each
 * position's own window (plan ruling R12). Weight = qty × entry × fx_entry; open positions are
 * marked to the latest daily close; BUY = exit/entry − 1, SELL = (entry − exit)/entry; FX and the
 * EUNL leg follow the same rule as the event measurement. Pure.
 */
public final class DepotBenchmarkCalculator {

    public record PositionInput(String strigoi, String symbol, String side, BigDecimal qty, BigDecimal entryPrice,
                                LocalDate entryDate, BigDecimal exitPrice, LocalDate exitDate) {
        public boolean open() {
            return exitDate == null;
        }
    }

    private record Measured(double weight, double ret, double bench) {}

    private static final class Acc {
        int positions;
        int open;
        int skipped;
        double capital;
        double ret;
        double bench;
    }

    private DepotBenchmarkCalculator() {
    }

    public static List<StrigoiDepotBenchmark> byStrigoi(List<PositionInput> positions,
                                                        Function<String, PriceSeries> instrumentBars,
                                                        Function<String, PriceSeries> fxByCurrency,
                                                        PriceSeries eunl) {
        Map<String, Acc> byStrigoi = new TreeMap<>();
        for (PositionInput p : positions) {
            Acc a = byStrigoi.computeIfAbsent(p.strigoi(), k -> new Acc());
            a.positions++;
            if (p.open()) a.open++;
            Measured m = measure(p, instrumentBars, fxByCurrency, eunl);
            if (m == null) {
                a.skipped++;
                continue;
            }
            a.capital += m.weight();
            a.ret += m.weight() * m.ret();
            a.bench += m.weight() * m.bench();
        }
        List<StrigoiDepotBenchmark> out = new ArrayList<>();
        byStrigoi.forEach((strigoi, a) -> {
            boolean measured = a.capital > 0;
            BigDecimal ret = measured ? frac(a.ret / a.capital) : null;
            BigDecimal bench = measured ? frac(a.bench / a.capital) : null;
            out.add(new StrigoiDepotBenchmark(strigoi, a.positions, a.open,
                    BigDecimal.valueOf(a.capital).setScale(2, RoundingMode.HALF_UP),
                    ret, bench, measured ? ret.subtract(bench) : null, a.skipped));
        });
        return out;
    }

    private static Measured measure(PositionInput p, Function<String, PriceSeries> instrumentBars,
                                    Function<String, PriceSeries> fxByCurrency, PriceSeries eunl) {
        if (p.qty() == null || p.entryPrice() == null || p.entryPrice().signum() <= 0 || p.entryDate() == null) {
            return null;
        }
        Optional<String> currency = CurrencyResolver.currencyOf(p.symbol());
        if (currency.isEmpty()) return null;
        BigDecimal exitPrice;
        LocalDate exitDate;
        if (p.open()) {
            PriceSeries s = instrumentBars.apply(p.symbol());
            if (s == null || s.isEmpty()) return null;
            exitPrice = s.lastBar().close();
            exitDate = s.lastBar().date();
        } else {
            exitPrice = p.exitPrice();
            exitDate = p.exitDate();
            if (exitPrice == null || exitPrice.signum() <= 0) return null;
        }
        double fxEntry = 1;
        double fxExit = 1;
        if (!CurrencyResolver.EUR.equals(currency.get())) {
            PriceSeries fx = fxByCurrency.apply(currency.get());
            if (fx == null || fx.isEmpty()) return null;
            Optional<OhlcBar> fe = fx.lastBefore(p.entryDate());
            Optional<OhlcBar> fo = fx.lastOnOrBefore(exitDate);
            if (fe.isEmpty() || fo.isEmpty()) return null;
            fxEntry = fe.get().close().doubleValue();
            fxExit = fo.get().close().doubleValue();
        }
        Optional<OhlcBar> b0 = eunl.lastBefore(p.entryDate());
        Optional<OhlcBar> b1 = eunl.lastOnOrBefore(exitDate);
        if (b0.isEmpty() || b1.isEmpty()) return null;
        double entry = p.entryPrice().doubleValue();
        double exit = exitPrice.doubleValue();
        double rLocal = "SELL".equalsIgnoreCase(p.side()) ? (entry - exit) / entry : exit / entry - 1;
        double rEur = (1 + rLocal) * fxExit / fxEntry - 1;
        double bench = b1.get().close().doubleValue() / b0.get().close().doubleValue() - 1;
        return new Measured(p.qty().doubleValue() * entry * fxEntry, rEur, bench);
    }

    private static BigDecimal frac(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
