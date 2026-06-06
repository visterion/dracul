package de.visterion.dracul.voievod;

import de.visterion.dracul.prey.Prey;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Groups open prey by symbol; keeps clusters confirmed by >= 2 distinct Strigoi. */
@Component
public class ConsensusDetector {

    public List<ConsensusCluster> detect(List<Prey> all, LocalDate today) {
        Map<String, List<Prey>> bySymbol = new TreeMap<>();
        for (Prey p : all) {
            if (p.symbol() == null || p.symbol().isBlank()) continue;
            if (!Horizons.isOpen(p.discoveredAt(), p.horizon(), today)) continue;
            bySymbol.computeIfAbsent(p.symbol().toUpperCase(), k -> new ArrayList<>()).add(p);
        }

        List<ConsensusCluster> clusters = new ArrayList<>();
        for (var entry : bySymbol.entrySet()) {
            List<Prey> group = entry.getValue();
            long distinctStrigoi = group.stream().map(Prey::discoveredBy).distinct().count();
            if (distinctStrigoi < 2) continue;
            clusters.add(new ConsensusCluster(entry.getKey(), mostRecentCompany(group), group));
        }
        return clusters;
    }

    private String mostRecentCompany(List<Prey> group) {
        return group.stream()
                .max(Comparator.comparing(p -> Horizons.dateOf(safeDate(p.discoveredAt()))))
                .map(Prey::companyName)
                .orElse(group.get(0).companyName());
    }

    /** dateOf throws on garbage; fall back so such prey never win "most recent". */
    private String safeDate(String discoveredAt) {
        try {
            Horizons.dateOf(discoveredAt);
            return discoveredAt;
        } catch (RuntimeException e) {
            return "0001-01-01";
        }
    }
}
