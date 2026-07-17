package de.visterion.dracul.daywalker.detect;

public enum TriggerType {
    PRICE_SPIKE,
    VOLUME_SPIKE,
    INSIDER_SELL,
    NEGATIVE_NEWS,
    ANALYST_DOWNGRADE,
    STOP_PROXIMITY,
    STOP_BREACHED,
    MACRO_PORTFOLIO // T2.2: macro fan-in on the whole portfolio (pseudo-symbol PORTFOLIO)
}
