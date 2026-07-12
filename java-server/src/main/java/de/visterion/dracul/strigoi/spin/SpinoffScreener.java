package de.visterion.dracul.strigoi.spin;

import de.visterion.dracul.hunting.agora.SpinoffFiling;
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
 *
 * <p>The dedup key is CIK-first, mirroring the V26 {@code spin_candidate}
 * natural-key index {@code COALESCE(cik, lower(company_name))} exactly: keyed on
 * the spin-co's registrant CIK when known, degrading to the lowercased company
 * name when the filing URL carried none. This collapses amendments of the same
 * spin-co (which share a CIK) into one candidate, matching the ingestion upsert
 * so the screener and the DB agree on identity.
 */
@Component
public class SpinoffScreener {

    public List<SpinCandidate> screen(List<SpinoffFiling> filings) {
        Map<String, SpinoffFiling> best = new LinkedHashMap<>();
        for (SpinoffFiling f : filings) {
            String key = naturalKey(f);
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
                    f.filingUrl(), f.cik()));
        }
        return out;
    }

    /** Mirrors the DB index {@code COALESCE(cik, lower(company_name))}: CIK when present,
     *  else the lowercased company name. */
    private static String naturalKey(SpinoffFiling f) {
        if (f.cik() != null && !f.cik().isBlank()) return f.cik();
        return f.companyName() == null ? "" : f.companyName().toLowerCase();
    }
}
