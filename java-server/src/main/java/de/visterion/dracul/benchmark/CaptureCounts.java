package de.visterion.dracul.benchmark;

/** Result of storing one batch of fetch items: stored rows, (agent, day, symbol) duplicates,
 *  and items without a usable symbol (skipped, never stored). */
public record CaptureCounts(int stored, int duplicate, int symbolLess) {

    public static final CaptureCounts ZERO = new CaptureCounts(0, 0, 0);

    public CaptureCounts plus(CaptureCounts o) {
        return new CaptureCounts(stored + o.stored, duplicate + o.duplicate, symbolLess + o.symbolLess);
    }
}
