package de.visterion.dracul.gropar;

/** Canonical fired-rule tokens shared by the indicator service, webhook controller, and agent output. */
public final class ExitRules {
    public static final String CHANDELIER_STOP = "CHANDELIER_STOP";
    public static final String DEATH_CROSS     = "DEATH_CROSS";
    public static final String TIME_STOP       = "TIME_STOP";
    public static final String PROFIT_TARGET   = "PROFIT_TARGET";
    public static final String STOP_LOSS       = "STOP_LOSS";
    public static final String INITIAL_STOP    = "INITIAL_STOP";
    public static final String GIVEBACK        = "GIVEBACK";
    private ExitRules() {}
}
