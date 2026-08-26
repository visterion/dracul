package de.visterion.dracul.executor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the real broker order ids, quantities, prices and R figures V46 is keyed on directly off
 * V46's own "-- FACT ..." comment lines, instead of retyping that data into test source. The
 * standing rule on this branch is that real broker data lives only in the migration itself (same
 * as V45's ACME/stop-1 synthetic test data keeps it out of ExecutorPositionLegBackfillIT) -- this
 * class is what lets the migration tests exercise those exact values without becoming a second
 * place where they're written down.
 */
final class V46Facts {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V46__close_stale_stopped_positions.sql");
    private static final Pattern FACT_LINE = Pattern.compile("^-- FACT (position|leg) (.*)$");
    private static final Pattern KV = Pattern.compile("(\\w+)=(\\S+)");

    record Position(long id, String symbol, BigDecimal qty, BigDecimal exitPrice,
            BigDecimal realizedR, BigDecimal rValue, Instant closedAt) {
    }

    record Leg(long positionId, int tranche, String stopOrderId, BigDecimal qty) {
    }

    private V46Facts() {
    }

    static Map<Long, Position> positions() throws IOException {
        Map<Long, Position> out = new LinkedHashMap<>();
        for (Map<String, String> kv : factLines("position")) {
            long id = Long.parseLong(kv.get("id"));
            out.put(id, new Position(id, kv.get("symbol"), new BigDecimal(kv.get("qty")),
                    new BigDecimal(kv.get("exit_price")), new BigDecimal(kv.get("realized_r")),
                    new BigDecimal(kv.get("r_value")),
                    OffsetDateTime.parse(kv.get("closed_at")).toInstant()));
        }
        return out;
    }

    static List<Leg> legs() throws IOException {
        List<Leg> out = new ArrayList<>();
        for (Map<String, String> kv : factLines("leg")) {
            out.add(new Leg(Long.parseLong(kv.get("position_id")), Integer.parseInt(kv.get("tranche")),
                    kv.get("stop_order_id"), new BigDecimal(kv.get("qty"))));
        }
        return out;
    }

    static List<Leg> legsFor(long positionId) throws IOException {
        return legs().stream().filter(l -> l.positionId() == positionId).toList();
    }

    private static List<Map<String, String>> factLines(String kind) throws IOException {
        List<Map<String, String>> results = new ArrayList<>();
        for (String rawLine : Files.readAllLines(MIGRATION)) {
            Matcher m = FACT_LINE.matcher(rawLine.strip());
            if (!m.matches() || !m.group(1).equals(kind)) {
                continue;
            }
            Map<String, String> kv = new LinkedHashMap<>();
            Matcher kvm = KV.matcher(m.group(2));
            while (kvm.find()) {
                kv.put(kvm.group(1), kvm.group(2));
            }
            results.add(kv);
        }
        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "V46Facts: no '-- FACT " + kind + " ...' lines found in " + MIGRATION
                            + " -- did the migration's comment format change?");
        }
        return results;
    }
}
