package de.visterion.dracul.benchmark;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The six capture hunters (spec 2026-10-13 §2.3). Momentum (ranking) and tech (book) are
 *  deliberately absent: they have no pre-screen to compare the LLM against. */
public final class ScreenItemMappers {

    public static final ScreenItemMapper ECHO = new ScreenItemMapper("strigoi-echo", "symbol", "sue", false);
    public static final ScreenItemMapper LAZARUS = new ScreenItemMapper("strigoi-lazarus", "symbol", "fScore", false);
    public static final ScreenItemMapper MERGER = new ScreenItemMapper("strigoi-merger", "symbol", "spreadPercent", true);
    public static final ScreenItemMapper INSIDER = new ScreenItemMapper("strigoi-insider", "ticker", "totalDollarValue", false);
    public static final ScreenItemMapper SPIN = new ScreenItemMapper("strigoi-spin", "symbol", "sizeRatio", false);
    public static final ScreenItemMapper INDEX = new ScreenItemMapper("strigoi-index", "symbol", "demandToAdvRatioEstimate", false);

    private static final Map<String, ScreenItemMapper> BY_AGENT = Map.of(
            ECHO.agent(), ECHO, LAZARUS.agent(), LAZARUS, MERGER.agent(), MERGER,
            INSIDER.agent(), INSIDER, SPIN.agent(), SPIN, INDEX.agent(), INDEX);

    private ScreenItemMappers() {
    }

    public static Optional<ScreenItemMapper> forAgent(String agent) {
        return agent == null ? Optional.empty() : Optional.ofNullable(BY_AGENT.get(agent));
    }

    public static Set<String> captureAgents() {
        return BY_AGENT.keySet();
    }
}
