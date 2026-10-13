package de.visterion.dracul.benchmark;

import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** One mapped pre-screen candidate. {@code score} is informational only (plan ruling R4);
 *  {@code extra} carries the merger offer and is null for every other hunter. */
public record ScreenItem(String symbol, BigDecimal score, ObjectNode extra) {
}
