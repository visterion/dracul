package de.visterion.dracul.voievod;

import de.visterion.dracul.marketdata.AgoraMarketData;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.verdict.ContributingStrigoiDetail;
import de.visterion.dracul.verdict.VerdictRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

@Component
public class VerdictSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(VerdictSynthesizer.class);

    public enum Result { INSERTED, UPDATED, NOOP_UNCHANGED, SKIPPED_DECIDED }

    private record PricePoint(BigDecimal price, String currency) {}

    private final VerdictRepository verdictRepo;
    private final AgoraMarketData marketData;

    public VerdictSynthesizer(VerdictRepository verdictRepo, AgoraMarketData marketData) {
        this.verdictRepo = verdictRepo;
        this.marketData = marketData;
    }

    public Result upsert(String symbol, String summary, ConsensusCluster cluster, String userId) {
        var prey = cluster.prey();
        var confidences = prey.stream().map(Prey::confidence).toList();
        double consensusScore = ConsensusScorer.noisyOr(confidences);
        double avgConfidence = ConsensusScorer.mean(confidences);

        var contributingStrigoi = prey.stream().map(Prey::discoveredBy).distinct().toList();
        var anomalyTypes = prey.stream().map(Prey::anomalyType).distinct().toList();
        var signals = prey.stream().flatMap(p -> p.signals().stream()).distinct().toList();
        var risks = prey.stream().flatMap(p -> p.risks().stream()).distinct().toList();
        var details = prey.stream()
                .map(p -> new ContributingStrigoiDetail(p.discoveredBy(), p.confidence(), p.thesis()))
                .toList();
        var preyIds = prey.stream().map(Prey::id).toList();
        String horizon = Horizons.longest(prey.stream().map(Prey::horizon).toList());
        PricePoint pp = resolvePrice(symbol);

        var active = verdictRepo.findActiveBySymbol(symbol, userId);
        if (active.isPresent()) {
            var a = active.get();
            if (a.decision() != null) {
                log.info("voievod: '{}' already decided ({}) — not overwriting", symbol, a.decision());
                return Result.SKIPPED_DECIDED;
            }
            if (sameSet(a.contributingPreyIds(), preyIds)) {
                return Result.NOOP_UNCHANGED;
            }
            verdictRepo.updateSynthesized(a.id(), cluster.companyName(), contributingStrigoi,
                    consensusScore, summary, anomalyTypes, pp.price(), pp.currency(), avgConfidence,
                    horizon, signals, risks, details, preyIds, userId);
            return Result.UPDATED;
        }
        verdictRepo.insertSynthesized(symbol, cluster.companyName(), contributingStrigoi,
                consensusScore, summary, anomalyTypes, pp.price(), pp.currency(), avgConfidence,
                horizon, signals, risks, details, preyIds, userId);
        return Result.INSERTED;
    }

    private PricePoint resolvePrice(String symbol) {
        try {
            var md = marketData.resolve(symbol);
            if (md == null) return new PricePoint(null, null);
            return new PricePoint(md.currentPrice(), md.currency());
        } catch (RuntimeException e) {
            log.warn("voievod: price lookup for {} failed: {}", symbol, e.getMessage());
            return new PricePoint(null, null);
        }
    }

    private boolean sameSet(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }
}
