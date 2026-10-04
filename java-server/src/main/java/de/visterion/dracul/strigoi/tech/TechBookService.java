package de.visterion.dracul.strigoi.tech;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;
import de.visterion.dracul.position.HeldPosition;
import de.visterion.dracul.position.HeldPositionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The basket's state (spec 2026-10-03 §4.2/§4.3). The executor repositories are reached through
 * {@link ObjectProvider} — they are {@code @ConditionalOnProperty(executor.enabled)}; without
 * them only depot holdings count as "held".
 */
@Component
@ConditionalOnProperty(value = "dracul.strigoi.tech.enabled", havingValue = "true")
public class TechBookService {

    public record Snapshot(boolean executorAvailable, List<ExecutorPosition> convictionOpen,
            List<ExecutorSignal> techPending, int acceptedThisWeek, Set<String> recentlyExited,
            Set<String> heldSymbols, Set<String> pendingSymbols) {

        /** basket-size − open CONVICTION − pending tech signals. */
        public int slotsFree(int basketSize) {
            return basketSize - convictionOpen.size() - techPending.size();
        }

        /** max-new-per-week − entries ACCEPTED this ISO week − pending tech signals (R2 Minor 6). */
        public int newAllowedThisWeek(int maxNewPerWeek) {
            return maxNewPerWeek - acceptedThisWeek - techPending.size();
        }

        public int capacity(int basketSize, int maxNewPerWeek) {
            return Math.max(0, Math.min(slotsFree(basketSize), newAllowedThisWeek(maxNewPerWeek)));
        }

        public boolean held(String symbol) { return heldSymbols.contains(norm(symbol)); }
        public boolean pending(String symbol) { return pendingSymbols.contains(norm(symbol)); }
        public boolean recentlyExited(String symbol) { return recentlyExited.contains(norm(symbol)); }

        static String norm(String s) {
            return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
        }
    }

    private final ObjectProvider<ExecutorPositionRepository> positions;
    private final ObjectProvider<ExecutorSignalRepository> signals;
    private final HeldPositionService held;
    private final TechSettings settings;
    private final Clock clock;

    @Autowired
    public TechBookService(ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals, HeldPositionService held,
            TechSettings settings) {
        this(positions, signals, held, settings, Clock.systemUTC());
    }

    TechBookService(ObjectProvider<ExecutorPositionRepository> positions,
            ObjectProvider<ExecutorSignalRepository> signals, HeldPositionService held,
            TechSettings settings, Clock clock) {
        this.positions = positions;
        this.signals = signals;
        this.held = held;
        this.settings = settings;
        this.clock = clock;
    }

    public Snapshot snapshot() {
        Set<String> heldSymbols = new HashSet<>();
        for (HeldPosition h : held.openPositions(settings.depotConnection())) {
            heldSymbols.add(Snapshot.norm(h.symbol()));
        }
        ExecutorPositionRepository pos = positions.getIfAvailable();
        ExecutorSignalRepository sig = signals.getIfAvailable();
        if (pos == null || sig == null) {
            return new Snapshot(false, List.of(), List.of(), 0, Set.of(), Set.copyOf(heldSymbols),
                    Set.of());
        }
        List<ExecutorPosition> open = pos.findOpen().stream()
                .filter(p -> settings.executorConnection().equals(p.connection()))
                .toList();
        open.forEach(p -> heldSymbols.add(Snapshot.norm(p.symbol())));
        List<ExecutorPosition> conviction = open.stream()
                .filter(p -> p.exitProfile() == ExitProfile.CONVICTION).toList();
        List<ExecutorSignal> pendingAll = sig.findPending(Integer.MAX_VALUE);
        List<ExecutorSignal> techPending = pendingAll.stream()
                .filter(s -> ExitProfile.fromMechanism(s.mechanism()) == ExitProfile.CONVICTION)
                .toList();
        Set<String> pendingSymbols = pendingAll.stream().map(s -> Snapshot.norm(s.symbol()))
                .collect(Collectors.toSet());
        int accepted = sig.countByMechanismAndStatusSince(ExitProfile.TECH_CONVICTION, "ACCEPTED",
                startOfIsoWeek());
        Set<String> recent = pos.findSymbolsClosedSince(ExitProfile.CONVICTION,
                        clock.instant().minus(Duration.ofDays(settings.reentryBlockDays())))
                .stream().map(Snapshot::norm).collect(Collectors.toSet());
        return new Snapshot(true, conviction, techPending, accepted, recent,
                Set.copyOf(heldSymbols), pendingSymbols);
    }

    Instant startOfIsoWeek() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
