package de.visterion.dracul.strigoi.lazarus;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic quality-at-52w-low pre-screen. Surfaces watchlist names trading
 * within {@code maxAboveLow} of their 52-week low that are not obviously
 * insolvent or over-levered. Intentionally lenient — the reasoning LLM does the
 * full Piotroski-style judgement on the metrics carried through to each
 * candidate. Pure / I/O-free: the controller fetches financials and builds the
 * {@link LazarusRaw} list.
 */
@Component
public class LazarusScreener {

    public List<LazarusCandidate> screen(List<LazarusRaw> raws, double maxAboveLow, double maxDebtEquity,
            double maxPriceToBook, double maxPFcf) {
        List<LazarusCandidate> out = new ArrayList<>();
        for (LazarusRaw r : raws) {
            BasicFinancials f = r.financials();
            if (f == null) continue;
            Double low = f.week52Low();
            if (low == null || low <= 0 || r.currentPrice() <= 0) continue;

            double pctAboveLow = (r.currentPrice() - low) / low;
            if (pctAboveLow > maxAboveLow) continue;

            // solvency: positive ROA OR positive free cash flow; both null → no evidence, skip
            boolean roaOk = f.roaTtm() != null && f.roaTtm() > 0;
            boolean fcfOk = f.fcfPerShare() != null && f.fcfPerShare() > 0;
            if (f.roaTtm() == null && f.fcfPerShare() == null) continue;
            if (!roaOk && !fcfOk) continue;

            // leverage: exclude only when present and above the cap
            if (f.debtToEquity() != null && f.debtToEquity() >= maxDebtEquity) continue;

            // valuation (cheapness) gate — Piotroski applies within the cheap universe.
            boolean pbCheap = f.priceToBook() != null && f.priceToBook() > 0 && f.priceToBook() <= maxPriceToBook;
            boolean fcfCheap = f.fcfPerShare() != null && f.fcfPerShare() > 0
                    && (r.currentPrice() / f.fcfPerShare()) <= maxPFcf;
            if (!pbCheap && !fcfCheap) continue;

            out.add(new LazarusCandidate(
                    r.symbol(), r.companyName(), r.currentPrice(),
                    low, f.week52High() == null ? 0.0 : f.week52High(), pctAboveLow,
                    f.roaTtm(), f.currentRatio(), f.debtToEquity(),
                    f.grossMargin(), f.netMargin(), f.revenueGrowthYoy(),
                    f.epsGrowthYoy(), f.priceToBook(), f.peTtm(), f.fcfPerShare()));
        }
        return out;
    }
}
