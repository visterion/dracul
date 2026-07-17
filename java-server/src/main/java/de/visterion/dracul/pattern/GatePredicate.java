package de.visterion.dracul.pattern;

import java.util.List;

/** A validated gate: an AND-set of 1..8 conditions over veto-time signal fields (spec T3.3 D1). */
public record GatePredicate(List<GateCondition> conditions) {}
