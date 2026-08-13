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

    public ScreenResult screen(List<LazarusRaw> raws, double maxAboveLow, double maxDebtEquity,
            double maxPriceToBook, double maxPFcf) {
        List<LazarusCandidate> out = new ArrayList<>();
        int implausibleRange = 0;
        for (LazarusRaw r : raws) {
            BasicFinancials f = r.financials();
            if (f == null) continue;
            Double low = f.week52Low();
            if (low == null || low <= 0 || r.currentPrice() <= 0) continue;

            double pctAboveLow = (r.currentPrice() - low) / low;
            // A price BELOW the 52-week low is definitionally impossible: either the price and
            // the low are quoted in different units (BRK.B returns the low in A-share units
            // against a price in B-share units), or one of the two numbers is simply wrong. The
            // upper-bound check alone lets a name sitting at its 52-week HIGH pass as "at the low".
            if (pctAboveLow < 0) { implausibleRange++; continue; }
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
            boolean cheapGatePassed = pbCheap || fcfCheap;

            // Size-dependent exemption, stage 1 of 2. The screener is pure and I/O-free, so it cannot
            // know which listing f.marketCap() describes or in which currency it is quoted — and
            // guessing that from the symbol shape is the bug this change removes. It therefore only
            // forwards the HOPE (a market cap exists at all); the controller resolves the listing,
            // converts to USD and applies the threshold. A candidate that clears the cheapness gate
            // needs no size at all.
            if (!cheapGatePassed && f.marketCap() == null) continue;

            out.add(new LazarusCandidate(
                    r.symbol(), r.companyName(), r.currentPrice(),
                    low, f.week52High() == null ? 0.0 : f.week52High(), pctAboveLow,
                    f.roaTtm(), f.currentRatio(), f.debtToEquity(),
                    f.grossMargin(), f.netMargin(), f.revenueGrowthYoy(),
                    f.epsGrowthYoy(), f.priceToBook(), f.peTtm(), f.fcfPerShare(),
                    f.marketCap(), f.reportingCurrency(), cheapGatePassed, ListingResolution.UNKNOWN));
        }
        return new ScreenResult(out, implausibleRange);
    }
}
