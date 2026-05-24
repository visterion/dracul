package de.visterion.dracul.verdict;

import jakarta.validation.constraints.Pattern;

public record DecisionRequest(
        @Pattern(regexp = "TRACK|INTERESTING|DISMISS|ACTED",
                 message = "must be TRACK, INTERESTING, DISMISS, ACTED, or null")
        String decision
) {}
