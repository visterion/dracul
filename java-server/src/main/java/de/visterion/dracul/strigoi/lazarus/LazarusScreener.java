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
            double maxPriceToBook, double maxPFcf, double megaCapUsdMillions) {
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

            // Size-dependent exemption: Piotroski's cheapness gate encodes its origin universe
            // (2000, the highest book-to-market quintile). A mega-cap never trades below twice its
            // book value — measured 2026-08-09, 17 of 122 S&P names above 100 Bn USD clear the gate,
            // and they are banks, oil and telecom. The cohort this hunter emitted in June
            // (MSFT/ADBE/CRM/NVDA/META, +36%/+36%/+28%/+7%/+5% by 2026-08-08) has been structurally
            // excluded since the gate landed. Loosens CHEAPNESS only, never QUALITY: this check
            // stands entirely inside the cheapness OR-chain, downstream of the solvency and
            // leverage gates above, which stay exactly as strict as before.
            //
            // Screener runs BEFORE enrichment (Task 2's marketCapUsdMillions is not yet available),
            // so it reads f.marketCap() directly — MILLIONS OF THE REPORTING CURRENCY — and only
            // trusts it when the reporting currency is null or USD. A non-USD name (e.g. 0941.HK,
            // ~226 Bn USD reported in CNY) does NOT get the exemption in this slice: comparing a raw
            // non-USD figure against a USD threshold would be right by accident and wrong in
            // principle, and dragging an FX dependency into this pure, I/O-free class is not worth
            // it for one gate. A missing marketCap never counts as big enough either — fail-closed.
            boolean usdReported = f.reportingCurrency() == null || "USD".equalsIgnoreCase(f.reportingCurrency());
            boolean megaCap = usdReported && f.marketCap() != null && f.marketCap() >= megaCapUsdMillions
                    && megaCapUsdMillions > 0;

            if (!pbCheap && !fcfCheap && !megaCap) continue;

            out.add(new LazarusCandidate(
                    r.symbol(), r.companyName(), r.currentPrice(),
                    low, f.week52High() == null ? 0.0 : f.week52High(), pctAboveLow,
                    f.roaTtm(), f.currentRatio(), f.debtToEquity(),
                    f.grossMargin(), f.netMargin(), f.revenueGrowthYoy(),
                    f.epsGrowthYoy(), f.priceToBook(), f.peTtm(), f.fcfPerShare(),
                    f.marketCap(), f.reportingCurrency()));
        }
        return new ScreenResult(out, implausibleRange);
    }
}
