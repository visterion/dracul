package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.edgar.SpinoffFiling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic spin-off pre-screen: de-duplicates multiple 10-12B filings of
 * the same spin-co (e.g. amendments) to the most recent one. No further filter
 * in v1 — the reasoning LLM judges the forced-selling setup. Recency is already
 * bounded by the adapter's date window.
 */
@Component
public class SpinoffScreener {

    public List<SpinCandidate> screen(List<SpinoffFiling> filings) {
        Map<String, SpinoffFiling> best = new LinkedHashMap<>();
        for (SpinoffFiling f : filings) {
            String key = (f.companyName() == null ? "" : f.companyName().toLowerCase())
                    + "|" + (f.ticker() == null ? "" : f.ticker().toUpperCase());
            SpinoffFiling cur = best.get(key);
            if (cur == null || f.filingDate().isAfter(cur.filingDate())) {
                best.put(key, f);
            }
        }
        List<SpinCandidate> out = new ArrayList<>();
        for (SpinoffFiling f : best.values()) {
            out.add(new SpinCandidate(
                    f.ticker(), f.companyName(), f.formType(),
                    f.filingDate() == null ? null : f.filingDate().toString(),
                    f.filingUrl()));
        }
        return out;
    }
}
