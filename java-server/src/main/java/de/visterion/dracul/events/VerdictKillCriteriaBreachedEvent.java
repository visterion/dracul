package de.visterion.dracul.events;

import java.util.List;

/** Published by {@link de.visterion.dracul.verdict.VerdictKillCriteriaWatcher} once per open
 *  verdict that has newly-breached kill criteria (not already persisted), so live consumers
 *  (SSE) can deliver a toast to exactly that owner. */
public record VerdictKillCriteriaBreachedEvent(
        String owner, String verdictId, String symbol, List<String> breached) {}
