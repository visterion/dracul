package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.marketdata.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Annotates screened merger candidates with the filing's summary term-sheet text (via Agora
 *  get_filing_text) and a recent price for the spread. Fail-soft: any lookup failure degrades
 *  that one field, never the run. Bounded to {@link #MAX} candidates per run.
 *
 *  <p>On top of the spread it derives the Mitchell &amp; Pulvino (2001) expected-value inputs.
 *  When the term sheet yields an {@code agreementDate}, one Agora daily-OHLC query per candidate
 *  supplies the pre-announcement <em>unaffected price</em> — the close of the last trading day
 *  strictly BEFORE the agreement date. This matters because the feed anchors on DEFM14A /
 *  SC TO-T filings that land weeks or months AFTER the deal was announced, so {@code lastPrice}
 *  is already the arb price; the agreement date ≈ announcement date is the correct anchor for
 *  the break cliff. The OHLC call mirrors the Lazarus latency guard: an <em>availability</em>
 *  failure ({@link AgoraUnavailableException} or {@link MarketDataException} of kind UNAVAILABLE)
 *  marks the source down for the remaining candidates of the batch, whereas a symbol-specific
 *  NOT_FOUND leaves the source up. */
@Component
public class MergerEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(MergerEnrichmentService.class);
    /** Merger deal filings are rare; cap defensively against a pathological batch. */
    static final int MAX = 25;
    /** Calendar days of OHLC requested to reach back before the agreement date. Agreements can
     *  be months old; ~400 days is comfortable headroom, and an agreement older than the window
     *  simply degrades {@code unaffectedPriceAvailable} to false rather than growing the query. */
    static final int OHLC_LOOKBACK_DAYS = 400;
    /** Days per year used to annualize the raw spread. */
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private final AgoraFilings filings;
    private final AgoraMarketData marketData;
    private final DealTermsParser dealTermsParser;

    public MergerEnrichmentService(AgoraFilings filings, AgoraMarketData marketData, DealTermsParser dealTermsParser) {
        this.filings = filings;
        this.marketData = marketData;
        this.dealTermsParser = dealTermsParser;
    }

    public List<EnrichedMergerCandidate> enrich(List<MergerCandidate> candidates) {
        List<MergerCandidate> capped = candidates.size() > MAX ? candidates.subList(0, MAX) : candidates;

        List<String> symbols = capped.stream()
                .map(MergerCandidate::symbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        Map<String, Quote> quotes = safeQuotes(symbols);

        List<EnrichedMergerCandidate> out = new ArrayList<>();
        boolean ohlcDown = false;
        for (MergerCandidate c : capped) {
            FilingText ft = safeFilingText(c.filingUrl());
            Quote q = c.symbol() == null ? null : quotes.get(c.symbol());
            // quotes() maps a missing/malformed price to BigDecimal.ZERO; treat a non-positive
            // price as unavailable so the LLM never computes a spread against 0.
            BigDecimal rawPrice = q == null ? null : q.price();
            boolean priceAvailable = rawPrice != null && rawPrice.signum() > 0;
            BigDecimal price = priceAvailable ? rawPrice : null;

            DealTerms terms = dealTermsParser.parse(ft.available() ? ft.text() : null);
            BigDecimal spread = null;
            if (terms.offerPrice() != null && price != null && price.signum() > 0) {
                spread = terms.offerPrice().subtract(price)
                        .divide(price, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            }

            // Unaffected pre-announcement price — only worth an OHLC round trip when there is an
            // agreement date to anchor it and a symbol to look up. No agreement date is a
            // symbol-specific miss (nothing to anchor on) and must NOT trip the source-down guard.
            BigDecimal unaffectedPrice = null;
            if (terms.agreementDate() != null && c.symbol() != null && !c.symbol().isBlank() && !ohlcDown) {
                try {
                    unaffectedPrice = unaffectedPriceFor(c.symbol(), terms.agreementDate());
                } catch (RuntimeException e) {
                    ohlcDown = markIfSourceDown(e, "ohlc history");
                    log.debug("merger enrichment: ohlc history unavailable for {}: {}", c.symbol(), e.getMessage());
                }
            }
            boolean unaffectedAvailable = unaffectedPrice != null;

            Integer daysToClose = terms.expectedCloseDate() == null ? null
                    : (int) ChronoUnit.DAYS.between(LocalDate.now(), terms.expectedCloseDate());
            BigDecimal annualizedSpread = annualizedSpread(spread, daysToClose);
            BigDecimal breakDownside = breakDownside(price, unaffectedPrice);

            out.add(new EnrichedMergerCandidate(
                    c.symbol(), c.companyName(), c.formType(), c.filingDate(), c.filingUrl(),
                    ft.text(), ft.available(), price, priceAvailable,
                    terms.offerPrice(), terms.considerationType(), terms.exchangeRatio(), terms.breakFee(), spread,
                    terms.agreementDate(), terms.expectedCloseDate(), terms.outsideDate(),
                    unaffectedPrice, unaffectedAvailable, daysToClose, annualizedSpread, breakDownside));
        }
        return out;
    }

    /** Close of the last trading day strictly before {@code agreementDate}, or null when the
     *  bounded OHLC window does not reach back that far (the agreement predates our lookback) or
     *  yields no usable bar before the anchor. */
    private BigDecimal unaffectedPriceFor(String symbol, LocalDate agreementDate) {
        List<OhlcBar> bars = marketData.dailyOhlcHistory(symbol, OHLC_LOOKBACK_DAYS);
        BigDecimal last = null; // bars are oldest-first; keep the newest positive close before the anchor
        for (OhlcBar b : bars) {
            if (b.date() == null || !b.date().isBefore(agreementDate)) continue;
            if (b.close() == null || b.close().signum() <= 0) continue;
            last = b.close();
        }
        return last;
    }

    /** {@code spreadPercent × 365 / daysToClose}; null unless both inputs are present and
     *  {@code daysToClose ≥ 1} (guards a divide-by-zero and nonsensical past/same-day closes). */
    private static BigDecimal annualizedSpread(BigDecimal spread, Integer daysToClose) {
        if (spread == null || daysToClose == null || daysToClose < 1) return null;
        return spread.multiply(DAYS_PER_YEAR)
                .divide(BigDecimal.valueOf(daysToClose), 2, RoundingMode.HALF_UP);
    }

    /** {@code (lastPrice − unaffectedPrice) / lastPrice × 100} — the price cliff if the deal
     *  breaks and the target reverts toward its pre-announcement level; null unless both prices
     *  are present and {@code lastPrice > 0}. */
    private static BigDecimal breakDownside(BigDecimal lastPrice, BigDecimal unaffectedPrice) {
        if (lastPrice == null || lastPrice.signum() <= 0 || unaffectedPrice == null) return null;
        return lastPrice.subtract(unaffectedPrice)
                .divide(lastPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /** True (and logs) when the failure is availability-related — the source's backend is down,
     *  so retrying it for the next candidate would only burn the webhook's latency budget.
     *  Symbol-specific failures (e.g. NOT_FOUND) leave the source up. */
    private static boolean markIfSourceDown(RuntimeException e, String source) {
        boolean availabilityFailure = e instanceof AgoraUnavailableException
                || (e instanceof MarketDataException m && m.kind() == MarketDataException.Kind.UNAVAILABLE);
        if (availabilityFailure) {
            log.warn("merger enrichment: {} source down ({}), skipping it for the remaining candidates",
                    source, e.getMessage());
        }
        return availabilityFailure;
    }

    /** {@link AgoraFilings#filingText} is already fail-soft, but wrap it too so an unforeseen
     *  runtime failure degrades one candidate rather than the whole run (mirrors EchoEnrichmentService). */
    private FilingText safeFilingText(String url) {
        try {
            return filings.filingText(url);
        } catch (Exception e) {
            log.debug("merger enrichment: filing text unavailable for {}: {}", url, e.getMessage());
            return FilingText.unavailable();
        }
    }

    private Map<String, Quote> safeQuotes(List<String> symbols) {
        if (symbols.isEmpty()) return Map.of();
        try {
            return marketData.quotes(symbols);
        } catch (Exception e) {
            log.debug("merger enrichment: quotes unavailable: {}", e.getMessage());
            return Map.of();
        }
    }
}
