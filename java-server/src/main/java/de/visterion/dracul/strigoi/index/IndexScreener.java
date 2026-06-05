package de.visterion.dracul.strigoi.index;

import de.visterion.dracul.hunting.wikipedia.Sp500Constituent;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic index-inclusion pre-screen: keeps S&P 500 constituents added within
 * the lookback window (the inclusion-drift candidates) and de-duplicates by symbol to
 * the most recent add date. No further filter — the routine-tier LLM judges whether
 * the drift window is still open.
 */
@Component
public class IndexScreener {

    public List<IndexCandidate> screen(List<Sp500Constituent> rows, int lookbackDays) {
        LocalDate cutoff = LocalDate.now().minusDays(lookbackDays);
        Map<String, Sp500Constituent> best = new LinkedHashMap<>();
        for (Sp500Constituent r : rows) {
            if (r.dateAdded() == null || r.dateAdded().isBefore(cutoff)) continue;
            String key = r.symbol() == null ? "" : r.symbol().toUpperCase();
            Sp500Constituent cur = best.get(key);
            if (cur == null || r.dateAdded().isAfter(cur.dateAdded())) {
                best.put(key, r);
            }
        }
        List<IndexCandidate> out = new ArrayList<>();
        for (Sp500Constituent r : best.values()) {
            out.add(new IndexCandidate(r.symbol(), r.companyName(), r.dateAdded().toString()));
        }
        return out;
    }
}
