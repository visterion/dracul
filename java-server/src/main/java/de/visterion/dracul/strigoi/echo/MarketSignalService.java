package de.visterion.dracul.strigoi.echo;

import de.visterion.dracul.marketdata.OhlcBar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure deterministic computation of SP2 market-reaction signals from daily OHLC:
 * market-adjusted announcement CAR (vs a market proxy, beta-adjusted when known),
 * abnormal report-day volume, 6-12 month price momentum, and average daily dollar
 * volume. No Spring state, no I/O — fully unit-tested. Missing data degrades to a
 * null field / {@link MarketSignals#empty()}, never an exception.
 *
 * <p>Window anchoring is deliberate and differs per signal:
 * <ul>
 *   <li><b>CAR</b> and <b>abnormal volume</b> are anchored to the report day (d0 =
 *       the first bar on/after {@code reportDate}) — they measure the announcement
 *       reaction. CAR needs a bar before d0 (d0 == 0 ⇒ no prior close ⇒ unavailable).</li>
 *   <li><b>Momentum</b> and <b>ADV</b> are anchored to the most recent bar (the end of
 *       the supplied series, i.e. "now"): momentum is the stock's trailing 6-12 month
 *       price momentum and ADV is current liquidity/capturability. Callers fetch OHLC
 *       up to the present, so the latest bar is today.</li>
 * </ul>
 */
@Component
public class MarketSignalService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int SCALE = 6;
    private static final int VOLUME_LOOKBACK = 20;
    private static final int ADV_LOOKBACK = 20;
    private static final int MOM_NEAR = 126; // ~6 months in trading days
    private static final int MOM_FAR = 252;  // ~12 months in trading days

    public MarketSignals compute(List<OhlcBar> stockBars, List<OhlcBar> marketBars,
                                 LocalDate reportDate, Double beta) {
        if (stockBars == null || stockBars.isEmpty() || reportDate == null) {
            return MarketSignals.empty();
        }
        double effBeta = (beta != null && beta > 0) ? beta : 1.0;

        // announcement-day index d0 = first bar on/after reportDate
        int d0 = -1;
        for (int i = 0; i < stockBars.size(); i++) {
            LocalDate d = stockBars.get(i).date();
            if (d != null && !d.isBefore(reportDate)) { d0 = i; break; }
        }

        Map<LocalDate, Integer> mIdx = new HashMap<>();
        if (marketBars != null) {
            for (int j = 0; j < marketBars.size(); j++) mIdx.put(marketBars.get(j).date(), j);
        }

        // --- CAR ---
        BigDecimal car1d = null, car3d = null;
        boolean carAvailable = false;
        if (d0 > 0) { // d0 == 0 ⇒ no prior close ⇒ CAR unavailable
            BigDecimal abn0 = abnormalReturn(stockBars, marketBars, mIdx, d0, effBeta);
            if (abn0 != null) {
                car1d = abn0.setScale(SCALE, RoundingMode.HALF_UP);
                carAvailable = true;
                BigDecimal sum = abn0;
                boolean full = true;
                for (int k = 1; k <= 2; k++) {
                    int idx = d0 + k;
                    if (idx >= stockBars.size()) { full = false; break; }
                    BigDecimal abn = abnormalReturn(stockBars, marketBars, mIdx, idx, effBeta);
                    if (abn == null) { full = false; break; }
                    sum = sum.add(abn);
                }
                if (full) car3d = sum.setScale(SCALE, RoundingMode.HALF_UP);
            }
        }

        // --- abnormal volume ---
        BigDecimal abnormalVolume = null;
        if (d0 >= VOLUME_LOOKBACK) {
            long sumVol = 0;
            for (int i = d0 - VOLUME_LOOKBACK; i < d0; i++) sumVol += stockBars.get(i).volume();
            if (sumVol > 0) {
                BigDecimal avg = BigDecimal.valueOf(sumVol).divide(BigDecimal.valueOf(VOLUME_LOOKBACK), MC);
                abnormalVolume = BigDecimal.valueOf(stockBars.get(d0).volume())
                        .divide(avg, MC).setScale(SCALE, RoundingMode.HALF_UP);
            }
        }

        // --- momentum 6-12m ---
        BigDecimal momentum = null;
        int n = stockBars.size();
        if (n - 1 - MOM_FAR >= 0) {
            BigDecimal cNear = stockBars.get(n - 1 - MOM_NEAR).close();
            BigDecimal cFar = stockBars.get(n - 1 - MOM_FAR).close();
            if (cFar.signum() != 0) {
                momentum = cNear.divide(cFar, MC).subtract(BigDecimal.ONE).setScale(SCALE, RoundingMode.HALF_UP);
            }
        }

        // --- ADV (dollar volume) ---
        BigDecimal adv = null;
        if (n >= ADV_LOOKBACK) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = n - ADV_LOOKBACK; i < n; i++) {
                OhlcBar b = stockBars.get(i);
                sum = sum.add(b.close().multiply(BigDecimal.valueOf(b.volume()), MC));
            }
            adv = sum.divide(BigDecimal.valueOf(ADV_LOOKBACK), MC).setScale(2, RoundingMode.HALF_UP);
        }

        return new MarketSignals(car1d, car3d, carAvailable, abnormalVolume, momentum, adv);
    }

    /**
     * Shared residual (abnormal) daily-return series: for every stock bar {@code i >= 1},
     * {@code stockRet(i) - beta*marketRet(sameDate)}, skipping any bar whose market return is
     * unresolvable (no aligned market bar / no prior close). Beta defaults to {@code 1.0} when null
     * or non-positive — the exact {@code effBeta} rule {@link #compute} applies — so the two callers
     * agree on the market-adjustment. Degenerate inputs (null/empty stock or market bars) yield an
     * empty list; never throws.
     *
     * <p>Extracted from {@link #compute}'s per-index {@link #abnormalReturn} building block so the
     * strigoi-index idiosyncratic-volatility snapshot can reuse the identical residual machinery.
     * Purely additive: {@code compute} is unchanged and keeps computing announcement-window CAR from
     * the same primitive, so the echo CAR suite is unaffected.
     */
    public static List<BigDecimal> residualReturns(List<OhlcBar> stockBars, List<OhlcBar> marketBars,
                                                   Double beta) {
        if (stockBars == null || stockBars.isEmpty() || marketBars == null || marketBars.isEmpty()) {
            return List.of();
        }
        double effBeta = (beta != null && beta > 0) ? beta : 1.0;
        Map<LocalDate, Integer> mIdx = new HashMap<>();
        for (int j = 0; j < marketBars.size(); j++) mIdx.put(marketBars.get(j).date(), j);

        List<BigDecimal> out = new ArrayList<>();
        for (int i = 1; i < stockBars.size(); i++) {
            BigDecimal abn = abnormalReturn(stockBars, marketBars, mIdx, i, effBeta);
            if (abn != null) out.add(abn);
        }
        return out;
    }

    /** Abnormal return on stock bar i: stockRet(i) - beta*marketRet(sameDate). null if unresolvable. */
    private static BigDecimal abnormalReturn(List<OhlcBar> stock, List<OhlcBar> market,
                                             Map<LocalDate, Integer> mIdx, int i, double beta) {
        if (i <= 0) return null;
        BigDecimal sPrev = stock.get(i - 1).close();
        BigDecimal sCur = stock.get(i).close();
        if (sPrev.signum() == 0) return null;
        BigDecimal stockRet = sCur.divide(sPrev, MC).subtract(BigDecimal.ONE);

        if (market == null) return null;
        Integer mj = mIdx.get(stock.get(i).date());
        if (mj == null) return null; // no market bar for this date
        if (mj <= 0) return null;    // market bar is first in series ⇒ no prior close
        BigDecimal mPrev = market.get(mj - 1).close();
        BigDecimal mCur = market.get(mj).close();
        if (mPrev.signum() == 0) return null;
        BigDecimal mktRet = mCur.divide(mPrev, MC).subtract(BigDecimal.ONE);

        return stockRet.subtract(mktRet.multiply(BigDecimal.valueOf(beta), MC));
    }
}
