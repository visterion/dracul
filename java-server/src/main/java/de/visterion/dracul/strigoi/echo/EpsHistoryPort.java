package de.visterion.dracul.strigoi.echo;

import java.util.List;

/** Historical quarterly diluted EPS, newest-first. Empty when unavailable. */
public interface EpsHistoryPort {
    List<QuarterlyEps> quarterlyEps(String symbol, int maxQuarters);
}
