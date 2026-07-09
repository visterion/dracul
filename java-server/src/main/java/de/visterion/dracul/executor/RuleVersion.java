package de.visterion.dracul.executor;

import tools.jackson.databind.JsonNode;

/** A named, versioned snapshot of the executor's exit-rule parameters. */
public record RuleVersion(
        String ruleVersion,
        String validFrom,
        String changes,
        String promptHash,
        JsonNode params) {
}
