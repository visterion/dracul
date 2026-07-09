package de.visterion.dracul.executor;

import de.visterion.dracul.prey.Prey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

    public PreySignalEmitter(PreySignalMapper mapper,
                             ExecutorSignalRepository signalRepo,
                             ExecutorPositionRepository positionRepo) {
        this.mapper = mapper;
        this.signalRepo = signalRepo;
        this.positionRepo = positionRepo;
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
            signalRepo.insert(mapper.map(p));
            pendingSymbols.add(symbol); // guard against duplicate symbols within this batch
            emitted++;
        }
        log.info("Emitted {} executor signal(s) from {} prey", emitted, preys.size());
    }
}
