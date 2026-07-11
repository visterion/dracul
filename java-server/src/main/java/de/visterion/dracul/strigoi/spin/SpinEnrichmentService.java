package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import de.visterion.dracul.hunting.agora.FilingText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Annotates screened spin-off candidates with the filing's summary term-sheet text (via Agora
 *  get_filing_text). Fail-soft: an unavailable filing degrades that one field, never the run.
 *  Bounded to {@link #MAX} candidates per run. */
@Component
public class SpinEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SpinEnrichmentService.class);
    /** Spin-off registrations are rare; cap defensively against a pathological batch. */
    static final int MAX = 25;

    private final AgoraFilings filings;
    private final SpinTermsParser spinTermsParser;

    public SpinEnrichmentService(AgoraFilings filings, SpinTermsParser spinTermsParser) {
        this.filings = filings;
        this.spinTermsParser = spinTermsParser;
    }

    public List<EnrichedSpinCandidate> enrich(List<SpinCandidate> candidates) {
        List<SpinCandidate> capped = candidates.size() > MAX ? candidates.subList(0, MAX) : candidates;
        List<EnrichedSpinCandidate> out = new ArrayList<>();
        for (SpinCandidate c : capped) {
            FilingText ft = safeFilingText(c.filingUrl());
            SpinTerms terms = spinTermsParser.parse(ft.available() ? ft.text() : null);
            out.add(new EnrichedSpinCandidate(
                    c.symbol(), c.companyName(), c.formType(), c.filingDate(), c.filingUrl(),
                    ft.text(), ft.available(),
                    terms.distributionRatio(), terms.recordDate(), terms.distributionDate()));
        }
        return out;
    }

    /** {@link AgoraFilings#filingText} is already fail-soft, but wrap it too so an unforeseen
     *  runtime failure degrades one candidate rather than the whole run (mirrors EchoEnrichmentService). */
    private FilingText safeFilingText(String url) {
        try {
            return filings.filingText(url);
        } catch (Exception e) {
            log.debug("spin enrichment: filing text unavailable for {}: {}", url, e.getMessage());
            return FilingText.unavailable();
        }
    }
}
