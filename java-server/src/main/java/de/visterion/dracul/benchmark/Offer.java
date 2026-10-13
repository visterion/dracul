package de.visterion.dracul.benchmark;

import java.math.BigDecimal;

/** Merger offer per share; {@code currency} null = the instrument's own currency. */
public record Offer(BigDecimal price, String currency) {
}
