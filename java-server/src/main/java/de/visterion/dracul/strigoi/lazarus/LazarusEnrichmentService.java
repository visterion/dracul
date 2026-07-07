package de.visterion.dracul.strigoi.lazarus;

import de.visterion.dracul.hunting.agora.AgoraFilings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Enriches screened quality-at-52w-low candidates with Agora's Piotroski F-Score. A candidate
 *  is hard-dropped when Agora reports it burns more cash than it books as net income (accruals
 *  red flag) — otherwise the enrichment is purely additive, degrading to a zero/unavailable
 *  score rather than dropping the candidate on any lookup failure. */
@Component
public class LazarusEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(LazarusEnrichmentService.class);

    private final AgoraFilings filings;

    public LazarusEnrichmentService(AgoraFilings filings) {
        this.filings = filings;
    }

    public List<EnrichedLazarusCandidate> enrich(List<LazarusCandidate> candidates) {
        var out = new ArrayList<EnrichedLazarusCandidate>();
        for (LazarusCandidate c : candidates) {
            FundamentalScore s;
            try {
                s = filings.fundamentalScore(c.symbol());
            } catch (Exception e) {
                log.debug("lazarus enrichment: fundamental score unavailable for {}: {}", c.symbol(), e.getMessage());
                s = FundamentalScore.unavailable();
            }
            if (s.cfoExceedsNetIncomeAvailable() && !s.cfoExceedsNetIncome()) {
                log.debug("lazarus enrichment dropped {}: cfo does not exceed net income (accruals)", c.symbol());
                continue; // accruals hard-drop
            }
            out.add(new EnrichedLazarusCandidate(
                    c.symbol(), c.companyName(), c.currentPrice(), c.week52Low(), c.week52High(),
                    c.pctAboveLow(), c.roaTtm(), c.currentRatio(), c.debtToEquity(), c.grossMargin(),
                    c.netMargin(), c.revenueGrowthYoy(), c.epsGrowthYoy(), c.priceToBook(), c.peTtm(),
                    c.fcfPerShare(), s.score(), s.criteriaAvailable(), s.accrualRatio(), s.cfoExceedsNetIncome()));
        }
        return out;
    }
}
