package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.AgoraUnavailableException;
import de.visterion.dracul.marketdata.MarketDataException;
import de.visterion.dracul.marketdata.OhlcBar;
import de.visterion.dracul.marketdata.Quote;
import de.visterion.dracul.strigoi.EnrichmentSourceGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *  that one field, never the run. Bounded to {@code dracul.strigoi.merger.max-candidates} per run;
 *  a cut, and every term sheet that could not be fetched, ride back on {@link EnrichedMergerBatch}
 *  so the fetch health can report them instead of looking clean.
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

    /**
     * Fallback for the test/convenience constructor; production reads
     * {@code dracul.strigoi.merger.max-candidates} (see the constructor and application.yaml).
     */
    static final int DEFAULT_MAX = 30;
    /** Fallback for the test/convenience constructor; production reads
     *  {@code dracul.strigoi.merger.term-sheet-digest-chars}. */
    static final int DEFAULT_DIGEST_CHARS = 700;
    /** Calendar days of OHLC requested to reach back before the agreement date. Agreements can
     *  be months old; ~400 days is comfortable headroom, and an agreement older than the window
     *  simply degrades {@code unaffectedPriceAvailable} to false rather than growing the query. */
    static final int OHLC_LOOKBACK_DAYS = 400;
    /** Days per year used to annualize the raw spread. */
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    private final AgoraFilings filings;
    private final AgoraMarketData marketData;
    private final DealTermsParser dealTermsParser;
    private final int maxCandidates;
    private final int digestChars;

    /**
     * @param maxCandidates payload bound on the candidate list, NOT a curation step — and DERIVED,
     *     not chosen. The binding ceiling is the Claude Code CLI's MCP output cap: every Vistierie
     *     tool reaches the model as an in-process SDK MCP tool, and a result over
     *     {@code MAX_MCP_OUTPUT_TOKENS} (25 000, unset on the bridge so the compiled default
     *     applies) is cut to {@code 25 000 x 4 = 100 000 characters}; below the pre-check's
     *     {@code 12 500}-token estimate — i.e. 50 000 characters — it is provably never touched.
     *     Against a 50 000-char budget, 5 000 reserved for the envelope, a measured worst case of
     *     645 chars of structured fields per candidate and a {@code digestChars}-sized deal
     *     digest, the arithmetic yields 30. Note what this is NOT: it is not 25 (that was binding
     *     — a 45-day and a 90-day window both returned exactly 25 rows) and it cannot be 40 (40 x
     *     645 = 25 800 chars of structured fields alone, before one character of deal text).
     *     {@code MergerPayloadBudgetTest} holds the full derivation and fails the build if the
     *     two knobs drift apart. A cut still reports {@code truncated}.
     * @param digestChars per-candidate ceiling on {@link TermSheetDigest}. The raw term sheet is
     *     NOT shipped: Agora caps it at 24 000 chars per filing, 25 of those made a 329 818-char
     *     tool result, and the model saw none of it. See {@link TermSheetDigest} for what is kept
     *     and why so little is needed.
     */
    @Autowired
    public MergerEnrichmentService(AgoraFilings filings, AgoraMarketData marketData,
                                   DealTermsParser dealTermsParser,
                                   @Value("${dracul.strigoi.merger.max-candidates:30}") int maxCandidates,
                                   @Value("${dracul.strigoi.merger.term-sheet-digest-chars:700}") int digestChars) {
        this.filings = filings;
        this.marketData = marketData;
        this.dealTermsParser = dealTermsParser;
        this.maxCandidates = maxCandidates;
        this.digestChars = digestChars;
    }

    /** Convenience form using {@link #DEFAULT_MAX} / {@link #DEFAULT_DIGEST_CHARS}. Spring must
     *  NOT pick this one, hence the explicit {@code @Autowired} on the five-arg constructor above
     *  (two constructors and no marker is an ambiguity failure at context startup). */
    MergerEnrichmentService(AgoraFilings filings, AgoraMarketData marketData, DealTermsParser dealTermsParser) {
        this(filings, marketData, dealTermsParser, DEFAULT_MAX, DEFAULT_DIGEST_CHARS);
    }

    /**
     * Enriches the screened candidates, reporting BOTH degradations it can introduce: the cap
     * cutting the list, and per-candidate term-sheet fetches that failed. Before this returned a
     * bare {@code List}, those losses had no channel to the fetch health and the run looked clean.
     */
    public EnrichedMergerBatch enrich(List<MergerCandidate> candidates) {
        boolean truncated = candidates.size() > maxCandidates;
        List<MergerCandidate> capped = truncated ? candidates.subList(0, maxCandidates) : candidates;
        if (truncated) {
            // EFTS returns file_date DESC, so the cut always drops the OLDEST deals — the ones a
            // widened lookback window was asked for in the first place.
            log.info("merger enrichment: {} candidates capped to {} (oldest deals dropped)",
                    candidates.size(), maxCandidates);
        }
        int filingTextFailures = 0;
        int oversizedFilings = 0;

        List<String> symbols = capped.stream()
                .map(MergerCandidate::symbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        Map<String, Quote> quotes = safeQuotes(symbols);

        List<EnrichedMergerCandidate> out = new ArrayList<>();
        var ohlc = EnrichmentSourceGuard.forSource("merger", "candidates", "ohlc history");
        for (MergerCandidate c : capped) {
            FilingText ft = safeFilingText(c.filingUrl());
            if (!ft.available()) {
                filingTextFailures++;
                if (ft.failure() == FilingText.Failure.TOO_LARGE) oversizedFilings++;
            }
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
            if (terms.agreementDate() != null && c.symbol() != null && !c.symbol().isBlank() && !ohlc.isDown()) {
                try {
                    unaffectedPrice = unaffectedPriceFor(c.symbol(), terms.agreementDate());
                    ohlc.recordSuccess();
                } catch (RuntimeException e) {
                    ohlc.recordFailure(e);
                    log.debug("merger enrichment: ohlc history unavailable for {}: {}", c.symbol(), e.getMessage());
                }
            }
            boolean unaffectedAvailable = unaffectedPrice != null;

            Integer daysToClose = terms.expectedCloseDate() == null ? null
                    : (int) ChronoUnit.DAYS.between(LocalDate.now(), terms.expectedCloseDate());
            BigDecimal annualizedSpread = annualizedSpread(spread, daysToClose);
            BigDecimal breakDownside = breakDownside(price, unaffectedPrice);

            // The RAW term sheet never leaves this method: DealTermsParser above has already
            // taken every quantitative field out of it, and shipping the remaining ~13 kB of
            // prose per candidate is what put the payload 3.3x over the bridge's truncation
            // ceiling and left the model reading nothing at all.
            String digest = ft.available() ? TermSheetDigest.of(ft.text(), digestChars) : "";

            out.add(new EnrichedMergerCandidate(
                    c.symbol(), c.companyName(), c.formType(), c.filingDate(), c.filingUrl(),
                    digest, ft.available(), price, priceAvailable,
                    terms.offerPrice(), terms.considerationType(), terms.exchangeRatio(), terms.breakFee(), spread,
                    terms.agreementDate(), terms.expectedCloseDate(), terms.outsideDate(),
                    unaffectedPrice, unaffectedAvailable, daysToClose, annualizedSpread, breakDownside));
        }
        if (filingTextFailures > 0) {
            log.info("merger enrichment: {} of {} term sheets unavailable ({} oversized documents)",
                    filingTextFailures, capped.size(), oversizedFilings);
        }
        return new EnrichedMergerBatch(List.copyOf(out), truncated, filingTextFailures, oversizedFilings);
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
