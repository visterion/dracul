package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.marketdata.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Annotates screened merger candidates with the filing's summary term-sheet text (via Agora
 *  get_filing_text) and a recent price for the spread. Fail-soft: any lookup failure degrades
 *  that one field, never the run. Bounded to {@link #MAX} candidates per run. */
@Component
public class MergerEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(MergerEnrichmentService.class);
    /** Merger deal filings are rare; cap defensively against a pathological batch. */
    static final int MAX = 25;

    private final AgoraFilings filings;
    private final AgoraMarketData marketData;

    public MergerEnrichmentService(AgoraFilings filings, AgoraMarketData marketData) {
        this.filings = filings;
        this.marketData = marketData;
    }

    public List<EnrichedMergerCandidate> enrich(List<MergerCandidate> candidates) {
        List<MergerCandidate> capped = candidates.size() > MAX ? candidates.subList(0, MAX) : candidates;

        List<String> symbols = capped.stream()
                .map(MergerCandidate::symbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        Map<String, Quote> quotes = safeQuotes(symbols);

        List<EnrichedMergerCandidate> out = new ArrayList<>();
        for (MergerCandidate c : capped) {
            FilingText ft = safeFilingText(c.filingUrl());
            Quote q = c.symbol() == null ? null : quotes.get(c.symbol());
            // quotes() maps a missing/malformed price to BigDecimal.ZERO; treat a non-positive
            // price as unavailable so the LLM never computes a spread against 0.
            BigDecimal rawPrice = q == null ? null : q.price();
            boolean priceAvailable = rawPrice != null && rawPrice.signum() > 0;
            BigDecimal price = priceAvailable ? rawPrice : null;
            out.add(new EnrichedMergerCandidate(
                    c.symbol(), c.companyName(), c.formType(), c.filingDate(), c.filingUrl(),
                    ft.text(), ft.available(), price, priceAvailable));
        }
        return out;
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
