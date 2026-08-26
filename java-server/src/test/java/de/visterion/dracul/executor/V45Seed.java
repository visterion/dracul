package de.visterion.dracul.executor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the real broker stop-order ids and per-leg quantities V45's two-tranche backfill is keyed
 * on directly off V45's own {@code _leg_qty_seed} VALUES lines, instead of retyping that data into
 * test source. Same technique — and same reason — as {@link V46Facts}: the standing rule on this
 * branch is that real broker data lives only in the migration itself, and this class is what lets
 * a migration test seed a fixture the real INSERT will actually match without becoming a second
 * place where those ids are written down.
 *
 * <p>Unlike V46, V45 has no separate {@code -- FACT} block: its VALUES lines already carry the
 * symbol and tranche in their trailing comment, and parsing those directly means the test can
 * never drift from the seed the migration ships.
 */
final class V45Seed {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V45__executor_position_leg.sql");

    /**
     * The shape of one seed line, shown with a deliberately SYNTHETIC id and quantity:
     * {@code ('stop-1', 10),    -- ACME  tranche 1, qty of the order ...}
     *
     * <p>A real pair would illustrate the format no better and would put a real broker order id
     * beside a real per-leg holding in the very file that exists so those live in V45 alone.
     * (The pattern itself requires digits for the id; the example is about the SHAPE.)
     */
    private static final Pattern SEED_LINE = Pattern.compile(
            "^\\('(\\d+)',\\s*([0-9.]+)\\)[,;]\\s*--\\s*(\\S+)\\s+tranche\\s+(\\d+)\\b.*$");

    record SeedLeg(String symbol, int tranche, String stopOrderId, BigDecimal qty) {
    }

    private V45Seed() {
    }

    /** Every seeded leg, in the order the migration lists them. */
    static List<SeedLeg> legs() throws IOException {
        List<SeedLeg> out = new ArrayList<>();
        for (String rawLine : Files.readAllLines(MIGRATION)) {
            Matcher m = SEED_LINE.matcher(rawLine.strip());
            if (!m.matches()) {
                continue;
            }
            out.add(new SeedLeg(m.group(3), Integer.parseInt(m.group(4)),
                    m.group(1), new BigDecimal(m.group(2))));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "V45Seed: no _leg_qty_seed VALUES lines found in " + MIGRATION
                            + " -- did the seed's line format change?");
        }
        return out;
    }

    /** The seeded legs grouped by symbol, insertion-ordered, for the symbols that carry two. */
    static Map<String, List<SeedLeg>> twoTrancheGroups() throws IOException {
        Map<String, List<SeedLeg>> grouped = new LinkedHashMap<>();
        for (SeedLeg leg : legs()) {
            grouped.computeIfAbsent(leg.symbol(), s -> new ArrayList<>()).add(leg);
        }
        grouped.values().removeIf(legs -> legs.size() != 2);
        if (grouped.isEmpty()) {
            throw new IllegalStateException(
                    "V45Seed: the seed in " + MIGRATION + " carries no symbol with two tranches, "
                            + "so the two-tranche backfill cannot be exercised against it");
        }
        return grouped;
    }

    /**
     * One seeded two-tranche group whose legs carry DIFFERENT quantities, tranche 1 first.
     *
     * <p>Asymmetry is what makes the fixture able to fail: an even split is exactly what a wrong
     * halving of the position's qty would also produce, so a symmetric group could not tell the
     * per-leg seed apart from the defect it replaced (V45's own comment: "the split was wrong on
     * both sides, and a sum-only check can never catch that").
     */
    static List<SeedLeg> firstAsymmetricTwoTrancheGroup() throws IOException {
        for (List<SeedLeg> group : twoTrancheGroups().values()) {
            List<SeedLeg> sorted = new ArrayList<>(group);
            sorted.sort((a, b) -> Integer.compare(a.tranche(), b.tranche()));
            if (sorted.get(0).tranche() != 1 || sorted.get(1).tranche() != 2) {
                throw new IllegalStateException(
                        "V45Seed: expected a tranche 1 and a tranche 2 in the seed group, got " + sorted);
            }
            if (sorted.get(0).qty().compareTo(sorted.get(1).qty()) != 0) {
                return sorted;
            }
        }
        throw new IllegalStateException(
                "V45Seed: every two-tranche group in " + MIGRATION + " is an even split, so no "
                        + "fixture built from it can distinguish a per-leg seed from a halving");
    }
}
