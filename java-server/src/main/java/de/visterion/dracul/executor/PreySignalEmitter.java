package de.visterion.dracul.executor;

import de.visterion.dracul.agent.PromptRegistry;
import de.visterion.dracul.prey.Prey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns freshly persisted {@link Prey} findings into pending
 * {@link ExecutorSignal}s so the executor has candidates to evaluate.
 *
 * <p>Only wired when the executor is enabled. Skips any prey whose symbol is
 * already an open position or already sits in a pending signal — the executor
 * should not be handed duplicate work.
 */
@Component
@ConditionalOnProperty(value = "dracul.executor.enabled", havingValue = "true")
public class PreySignalEmitter {

    private static final Logger log = LoggerFactory.getLogger(PreySignalEmitter.class);

    private final PreySignalMapper mapper;
    private final ExecutorSignalRepository signalRepo;
    private final ExecutorPositionRepository positionRepo;
    private final ExecutorIndicators indicators;
    private final PromptRegistry registry;
    private final AgentVersionResolver versions;
    private final int atrPeriod;
    private final int swingPeriod;

    public PreySignalEmitter(PreySignalMapper mapper,
                             ExecutorSignalRepository signalRepo,
                             ExecutorPositionRepository positionRepo,
                             ExecutorIndicators indicators,
                             PromptRegistry registry,
                             AgentVersionResolver versions,
                             @Value("${dracul.executor.atr-period:22}") int atrPeriod,
                             @Value("${dracul.executor.swing-period:20}") int swingPeriod) {
        this.mapper = mapper;
        this.signalRepo = signalRepo;
        this.positionRepo = positionRepo;
        this.indicators = indicators;
        this.registry = registry;
        this.versions = versions;
        this.atrPeriod = atrPeriod;
        this.swingPeriod = swingPeriod;
    }

    public void emit(List<Prey> preys) {
        if (preys == null || preys.isEmpty()) return;

        Set<String> openSymbols = positionRepo.findOpen().stream()
                .map(ExecutorPosition::symbol)
                .collect(Collectors.toSet());
        Set<String> pendingSymbols = signalRepo.findPending(Integer.MAX_VALUE).stream()
                .map(ExecutorSignal::symbol)
                .collect(Collectors.toSet());

        int emitted = 0;
        for (Prey p : preys) {
            String symbol = p.symbol();
            if (openSymbols.contains(symbol)) {
                log.debug("Skipping prey {} — already an open position", symbol);
                continue;
            }
            if (pendingSymbols.contains(symbol)) {
                log.debug("Skipping prey {} — already a pending signal", symbol);
                continue;
            }
            ExecutorSignal s = mapper.map(p);
            if (!isKnownVersion(s.source(), s.agentVersion())) {
                log.warn("Skipping prey {} — UNKNOWN_VERSION: agent {} version {} not in registry",
                        symbol, s.source(), s.agentVersion());
                continue;
            }
            ExecutorIndicators.Levels lv = indicators.levels(symbol, atrPeriod, swingPeriod);
            BigDecimal ref = lv.available() ? lv.referencePrice() : null;
            signalRepo.insert(new ExecutorSignal(s.signalId(), s.source(), s.agentVersion(),
                    s.symbol(), s.direction(), s.confidence(), s.mechanism(), s.killCriteria(),
                    s.horizon(), ref, s.status(), s.createdAt()));
            pendingSymbols.add(symbol); // guard against duplicate symbols within this batch
            emitted++;
        }
        log.info("Emitted {} executor signal(s) from {} prey", emitted, preys.size());
    }

    /**
     * "operator" is exempt — manual signals carry no prompt hash. Otherwise the version
     * must be a registry-known body hash, or the live-DB fallback keeps legitimately
     * user-edited prompts (registry miss, but matching the agent's current stored prompt)
     * working.
     */
    private boolean isKnownVersion(String source, String version) {
        if ("operator".equals(source)) return true;
        return registry.knownHashes().contains(version) || versions.versionFor(source).equals(version);
    }
}
