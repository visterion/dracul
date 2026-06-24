package de.visterion.dracul.strigoi.echo;

/** SUE result for one candidate. value/decile null when not computable. */
public record Sue(Double value, Integer decile, boolean approximate, boolean available) {
    public static Sue unavailable() { return new Sue(null, null, true, false); }
}
