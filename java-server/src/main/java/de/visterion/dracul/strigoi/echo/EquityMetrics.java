package de.visterion.dracul.strigoi.echo;

/** SP2 per-symbol equity metrics for confidence dampening / risk-adjustment.
 *  Nullable field = unknown; {@code available} false = the whole lookup failed.
 *  When {@code available} is true individual fields may still be null (the provider
 *  omitted them); only the overall lookup succeeded.
 *  {@code week52Low}/{@code week52High} come free from the same /stock/metric call and
 *  are reserved for a future price-extension rubric; they are not yet surfaced to the LLM. */
public record EquityMetrics(
        Double beta,
        Double marketCap,
        Double week52Low,
        Double week52High,
        String sector,
        boolean available
) {
    public static EquityMetrics unavailable() {
        return new EquityMetrics(null, null, null, null, null, false);
    }
}
