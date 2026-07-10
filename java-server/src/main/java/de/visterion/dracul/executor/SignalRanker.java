package de.visterion.dracul.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the pending-signal queue before it reaches the executor LLM. The agent works the queue
 * top-down and stops once it fills its position budget for the run, so the order returned here
 * directly decides which candidates ever get a look. Order (highest priority first):
 *
 * <ol>
 *   <li>Signals whose {@code mechanism} is <em>not</em> already represented among the currently
 *       open positions (portfolio diversification -- avoid piling into the same anomaly type).
 *   <li>Confidence, descending.
 *   <li>{@code createdAt}, descending (freshest first -- most remaining runway for
 *       time-decaying anomalies such as PEAD or index-inclusion drift).
 * </ol>
 *
 * <p>Pure and stateless: no I/O, no repository access. The caller assembles {@code openMechanisms}
 * (see {@link #openMechanisms}) and passes it in alongside the raw pending/open lists.
 */
@Service
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class SignalRanker {

    public List<ExecutorSignal> rank(List<ExecutorSignal> pending, List<ExecutorPosition> open,
            Map<String, String> openMechanisms) {
        Set<String> heldMechanisms = new HashSet<>(openMechanisms.values());

        List<ExecutorSignal> ranked = new ArrayList<>(pending);
        ranked.sort((a, b) -> {
            int byNovelty = Boolean.compare(
                    heldMechanisms.contains(a.mechanism()), heldMechanisms.contains(b.mechanism()));
            if (byNovelty != 0) return byNovelty;

            int byConfidence = compareConfidenceDesc(a.confidence(), b.confidence());
            if (byConfidence != 0) return byConfidence;

            return compareCreatedAtDesc(a.createdAt(), b.createdAt());
        });
        return ranked;
    }

    /**
     * Symbol -> mechanism of the signal each open position was entered from, mirroring the
     * mapping {@link EntryContextAssembler#assemble} builds inline. Left un-extracted there: the
     * assembler's loop interleaves this lookup with unrelated exposure/heat accumulation over the
     * same {@code openPositions} iteration, so pulling it out would mean either iterating twice or
     * threading an accumulator object through a shared helper -- not a trivial extraction for a
     * ranking-only concern, so the assembler keeps its own inline copy.
     */
    static Map<String, String> openMechanisms(List<ExecutorPosition> open, ExecutorSignalRepository signalRepo) {
        Map<String, String> mechanisms = new LinkedHashMap<>();
        for (ExecutorPosition p : open) {
            ExecutorSignal source = p.sourceSignalId() != null ? signalRepo.findById(p.sourceSignalId()) : null;
            if (source != null && source.mechanism() != null) {
                mechanisms.put(p.symbol(), source.mechanism());
            }
        }
        return mechanisms;
    }

    private static int compareConfidenceDesc(Double a, Double b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Double.compare(b, a);
    }

    // createdAt is a String (ISO-ish timestamp straight from Postgres), not a parsed temporal
    // type. Given the uniform DB format (fixed-width, same offset representation for every row),
    // lexicographic string comparison order matches chronological order, so a null-safe string
    // compare is sufficient here -- no need to parse into an Instant. Nulls sort last.
    private static int compareCreatedAtDesc(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }
}
