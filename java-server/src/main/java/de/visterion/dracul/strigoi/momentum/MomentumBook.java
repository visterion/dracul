package de.visterion.dracul.strigoi.momentum;

import de.visterion.dracul.executor.ExecutorPosition;
import de.visterion.dracul.executor.ExecutorPositionRepository;
import de.visterion.dracul.executor.ExecutorSignal;
import de.visterion.dracul.executor.ExecutorSignalRepository;
import de.visterion.dracul.executor.ExitProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The executor book as strigoi-momentum sees it (spec 2026-10-04 §5.3): OPEN MOMENTUM rows on the
 * executor connection, symbols held or pending under ANOTHER profile ({@code held_elsewhere} —
 * the executor book only, never the user's depot), and pending signals. Symbols upper-cased.
 */
public record MomentumBook(boolean executorAvailable, List<ExecutorPosition> momentumOpen,
        Set<String> heldElsewhere, Set<String> pendingMomentum, Set<String> pendingAny) {

    public static final MomentumBook EMPTY = new MomentumBook(false, List.of(), Set.of(), Set.of(), Set.of());

    public static MomentumBook read(ExecutorPositionRepository positions,
            ExecutorSignalRepository signals, String connection) {
        if (positions == null || signals == null) return EMPTY;
        List<ExecutorPosition> momentum = new ArrayList<>();
        Set<String> elsewhere = new HashSet<>();
        for (ExecutorPosition p : positions.findOpen()) {
            if (!connection.equals(p.connection())) continue;
            if (p.profile() == ExitProfile.MOMENTUM) momentum.add(p);
            else elsewhere.add(norm(p.symbol()));
        }
        Set<String> pendingMomentum = new HashSet<>();
        Set<String> pendingAny = new HashSet<>();
        for (ExecutorSignal s : signals.findPending(Integer.MAX_VALUE)) {
            String symbol = norm(s.symbol());
            pendingAny.add(symbol);
            if (ExitProfile.fromMechanism(s.mechanism()) == ExitProfile.MOMENTUM) pendingMomentum.add(symbol);
            else elsewhere.add(symbol);
        }
        return new MomentumBook(true, List.copyOf(momentum), Set.copyOf(elsewhere),
                Set.copyOf(pendingMomentum), Set.copyOf(pendingAny));
    }

    public boolean heldMomentum(String symbol) {
        String s = norm(symbol);
        return momentumOpen.stream().anyMatch(p -> norm(p.symbol()).equals(s));
    }

    public static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
