package de.visterion.dracul.strigoi.merger;

import de.visterion.dracul.hunting.agora.MergerFiling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic merger pre-screen: de-duplicates multiple deal filings of the
 * same target (e.g. an amended proxy after a preliminary one) to the most recent
 * one. No further filter in v1 — the reasoning LLM judges the merger-arb setup
 * (spread, closing probability). Recency is bounded by the adapter's date window.
 */
@Component
public class MergerScreener {

    public List<MergerCandidate> screen(List<MergerFiling> filings) {
        Map<String, MergerFiling> best = new LinkedHashMap<>();
        for (MergerFiling f : filings) {
            String key = (f.companyName() == null ? "" : f.companyName().toLowerCase())
                    + "|" + (f.ticker() == null ? "" : f.ticker().toUpperCase());
            MergerFiling cur = best.get(key);
            if (cur == null || f.filingDate().isAfter(cur.filingDate())) {
                best.put(key, f);
            }
        }
        List<MergerCandidate> out = new ArrayList<>();
        for (MergerFiling f : best.values()) {
            out.add(new MergerCandidate(
                    f.ticker(), f.companyName(), f.formType(),
                    f.filingDate() == null ? null : f.filingDate().toString(),
                    f.filingUrl()));
        }
        return out;
    }
}
