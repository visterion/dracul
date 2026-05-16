package de.visterion.dracul.chronicle;

import de.visterion.dracul.pattern.Pattern;
import de.visterion.dracul.prey.Prey;
import de.visterion.dracul.verdict.Verdict;

import java.util.List;

public record ChronicleData(
        List<Prey> prey,
        List<Verdict> verdicts,
        List<DaywalkerAlert> alerts,
        List<Pattern> pendingPatterns) {}
