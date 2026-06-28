package de.visterion.dracul.strigoi.echo;

/** Analyst-revision proxy from recommendation-trend movement. {@code available} false = unknown.
 *  {@code netProxy} = latestNet - previousNet (net = strongBuy+buy-sell-strongSell);
 *  {@code direction} = "up" | "down" | "flat". */
public record EarningsRevisions(Integer netProxy, String direction, boolean available) {
    public static EarningsRevisions unavailable() {
        return new EarningsRevisions(null, null, false);
    }
}
