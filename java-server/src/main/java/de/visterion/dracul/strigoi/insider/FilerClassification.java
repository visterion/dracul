package de.visterion.dracul.strigoi.insider;

/**
 * Cohen, Malloy &amp; Pomorski (2012) routine/opportunistic split for an insider's open-market
 * purchases. The documented predictive alpha sits in OPPORTUNISTIC (pattern-deviating) buys;
 * ROUTINE buyers (a fixed calendar cadence) carry no forecasting power.
 *
 * <ul>
 *   <li>{@link #ROUTINE} — the insider buys on a recurring calendar cadence (same month year
 *       after year), so this buy is expected and uninformative.</li>
 *   <li>{@link #OPPORTUNISTIC} — no such cadence in a sufficient history: a discretionary,
 *       potentially information-driven buy.</li>
 *   <li>{@link #UNKNOWN} — too little history to judge (thin history, a truncated/incomplete
 *       owner history, or no matching owner). Deliberately NOT counted as opportunistic: doing
 *       so would score noise as signal.</li>
 * </ul>
 */
public enum FilerClassification {
    ROUTINE,
    OPPORTUNISTIC,
    UNKNOWN
}
