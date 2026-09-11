package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven: every case is one row built by {@link #row}, whose {@code role} and {@code status}
 * are derived from the raw {@code role}/{@code type}/{@code status} strings by the SAME rules
 * {@code AgoraExecutionGateway.roleOf}/{@code toStatus} apply, so a fixture here cannot describe an
 * order the gateway could never produce.
 */
class AdoptionCandidatesTest {

    private static OrderRole roleFor(String role, String type) {
        if (role != null && !"other".equalsIgnoreCase(role)) {
            return switch (role) {
                case "entry" -> OrderRole.ENTRY;
                case "stop_loss" -> OrderRole.STOP_LOSS;
                case "take_profit" -> OrderRole.TAKE_PROFIT;
                default -> OrderRole.OTHER;
            };
        }
        if (type == null) return OrderRole.OTHER;
        return switch (type) {
            case "stopiftraded", "stop" -> OrderRole.STOP_LOSS;
            default -> OrderRole.OTHER;
        };
    }

    private static OrderStatus statusFor(String rawStatus) {
        if (rawStatus == null) return OrderStatus.WORKING;
        return switch (rawStatus) {
            case "filled", "finalfill" -> OrderStatus.FILLED;
            case "partially_filled", "partial", "partialfill" -> OrderStatus.PARTIALLY_FILLED;
            case "cancelled", "canceled" -> OrderStatus.CANCELLED;
            case "rejected" -> OrderStatus.REJECTED;
            default -> OrderStatus.WORKING; // incl. working/placed/changed/notworking/unknown
        };
    }

    private static BrokerOrder row(String id, String side, String type, String rawStatus,
            String source, String role, String filledQty, String avgFillPrice, String stopPrice,
            String filledAt) {
        return new BrokerOrder(id, "sig-1", "ACME", roleFor(role, type), statusFor(rawStatus),
                new BigDecimal("10"),
                filledQty == null ? null : new BigDecimal(filledQty),
                avgFillPrice == null ? null : new BigDecimal(avgFillPrice),
                null, side, type, rawStatus, source,
                null,
                stopPrice == null ? null : new BigDecimal(stopPrice),
                filledAt == null ? null : Instant.parse(filledAt));
    }

    private static BrokerOrder openRow(String id, String side, String type, String rawStatus) {
        return row(id, side, type, rawStatus, "open", "other", null, null, null, null);
    }

    private static BrokerOrder fill(String id, String side, String type, String filledAt) {
        return row(id, side, type, "finalfill", "history", "other", "10", "100", null, filledAt);
    }

    @Test void emptyListYieldsAllEmpty() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.filledEntry()).isNull();
        assertThat(c.filledUnverifiable()).isNull();
        assertThat(c.terminalExit()).isNull();
        assertThat(c.stopLeg()).isNull();
        assertThat(c.unclaimedOpen()).isEmpty();
    }

    @Test void aWorkingSameSideEntryIsTheWorkingCandidateAndBeatsAFill() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                openRow("ord-working", "buy", "limit", "working"),
                fill("ord-fill", "buy", "limit", "2026-09-08T14:00:00Z")), "buy");

        assertThat(c.working().orderId()).isEqualTo("ord-working");
        assertThat(c.filledEntry().orderId()).isEqualTo("ord-fill");
    }

    @Test void aDeadPlacedHistoryRowIsNeverWorking() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-dead", "buy", "limit", "placed", "history", "other",
                        null, null, null, null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.filledEntry()).isNull();
        assertThat(c.unclaimedOpen()).isEmpty();
    }

    @Test void aWorkingStopLegIsNeverTheWorkingEntry() {
        // E2 shape 1: a surviving protective stop under the entry's own clientRef.
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-stop", "sell", "stopiftraded", "working", "open", "other",
                        null, null, "90", null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.stopLeg().orderId()).isEqualTo("ord-stop");
    }

    @Test void aWorkingSameSideStopIsNeverTheWorkingEntry() {
        // Open, live, entry-side (strictSide true) — but it is a stop, so it must not be
        // adopted as the working entry. It also cannot be the stopLeg (stopLeg needs the
        // EXIT side), so it lands in unclaimedOpen: something the guard must not place next to.
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-same-side-stop", "buy", "stopiftraded", "working", "open", "other",
                        null, null, "90", null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.stopLeg()).isNull();
        assertThat(c.unclaimedOpen()).extracting(BrokerOrder::orderId)
                .containsExactly("ord-same-side-stop");
    }

    @Test void aWorkingTakeProfitLegIsNeverTheWorkingEntry() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-tp-blank", "", "limit", "working", "open", "take_profit",
                        null, null, null, null),
                row("ord-tp-exit", "sell", "limit", "working", "open", "take_profit",
                        null, null, null, null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.stopLeg()).isNull();
        assertThat(c.unclaimedOpen()).extracting(BrokerOrder::orderId)
                .containsExactly("ord-tp-blank", "ord-tp-exit");
    }

    @Test void aBlankSideFinalfillLimitIsNeverTheFilledEntry() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-blank", "", "limit", "finalfill", "history", "other",
                        "10", "100", null, "2026-09-08T14:00:00Z")), "buy");

        assertThat(c.filledEntry()).isNull();
        assertThat(c.filledUnverifiable()).isNull();
        // A blank side matches looseSide(exitSide), so it counts as a terminal exit — the
        // refusing classification, which is the safe reading of an unattributable fill.
        assertThat(c.terminalExit().orderId()).isEqualTo("ord-blank");
    }

    @Test void aNotworkingChildIsNeitherWorkingNorStopLeg() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-child", "", "stopiftraded", "notworking", "open", "stop_loss",
                        null, null, "90", null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.stopLeg()).isNull();
        assertThat(c.unclaimedOpen()).extracting(BrokerOrder::orderId).containsExactly("ord-child");
    }

    @Test void aFilledEntryWithoutAFillPriceIsUnverifiable() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-fill", "buy", "limit", "finalfill", "history", "other",
                        "10", null, null, "2026-09-08T14:00:00Z")), "buy");

        assertThat(c.filledEntry()).isNull();
        assertThat(c.filledUnverifiable().orderId()).isEqualTo("ord-fill");
    }

    @Test void aFilledEntryWithoutAFilledQtyIsUnverifiable() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-fill", "buy", "limit", "finalfill", "history", "other",
                        null, "100", null, "2026-09-08T14:00:00Z")), "buy");

        assertThat(c.filledEntry()).isNull();
        assertThat(c.filledUnverifiable().orderId()).isEqualTo("ord-fill");
    }

    @Test void filledUnverifiableCoexistsWithAFilledEntryFromAnotherRow() {
        // One complete fill (qualifies as filledEntry) and one incomplete same-side fill
        // (qualifies as filledUnverifiable) under the same ref: both must be populated
        // independently, not just whichever one is found first.
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                fill("ord-complete", "buy", "limit", "2026-09-08T14:00:00Z"),
                row("ord-incomplete", "buy", "limit", "finalfill", "history", "other",
                        "10", null, null, "2026-09-09T14:00:00Z")), "buy");

        assertThat(c.filledEntry().orderId()).isEqualTo("ord-complete");
        assertThat(c.filledUnverifiable().orderId()).isEqualTo("ord-incomplete");
    }

    @Test void theFilledEntryIsTheEarliestSameSideNonStopFillAndNeverTheTakeProfit() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                fill("ord-late", "buy", "limit", "2026-09-09T14:00:00Z"),
                fill("ord-early", "buy", "limit", "2026-09-08T14:00:00Z"),
                row("ord-tp", "buy", "limit", "finalfill", "history", "take_profit",
                        "10", "120", null, "2026-09-07T14:00:00Z")), "buy");

        assertThat(c.filledEntry().orderId()).isEqualTo("ord-early");
    }

    @Test void fillsWithNoTimestampSortLastAndThenByOrderId() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                fill("ord-b", "buy", "limit", null),
                fill("ord-a", "buy", "limit", null)), "buy");

        assertThat(c.filledEntry().orderId()).isEqualTo("ord-a");
    }

    @Test void aTimestampedFillBeatsAnUntimestampedOne() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                fill("ord-a", "buy", "limit", null),
                fill("ord-z", "buy", "limit", "2026-09-09T14:00:00Z")), "buy");

        assertThat(c.filledEntry().orderId()).isEqualTo("ord-z");
    }

    @Test void aMarketEntryFillIsAFilledEntry() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                fill("ord-mkt", "buy", "market", "2026-09-08T14:00:00Z")), "buy");

        assertThat(c.filledEntry().orderId()).isEqualTo("ord-mkt");
    }

    @Test void sellMirror() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                openRow("ord-working", "sell", "limit", "working"),
                fill("ord-fill", "sell", "limit", "2026-09-08T14:00:00Z"),
                row("ord-stop", "buy", "stopiftraded", "working", "open", "other",
                        null, null, "110", null),
                fill("ord-exit", "buy", "stopiftraded", "2026-09-09T14:00:00Z")), "sell");

        assertThat(c.working().orderId()).isEqualTo("ord-working");
        assertThat(c.filledEntry().orderId()).isEqualTo("ord-fill");
        assertThat(c.stopLeg().orderId()).isEqualTo("ord-stop");
        assertThat(c.terminalExit().orderId()).isEqualTo("ord-exit");
    }

    @Test void aFilledStopAndAFilledTakeProfitAreBothTerminalExits() {
        AdoptionCandidates stopped = AdoptionCandidates.classify(List.of(
                fill("ord-entry", "buy", "limit", "2026-09-08T14:00:00Z"),
                fill("ord-stop", "sell", "stopiftraded", "2026-09-09T14:00:00Z")), "buy");
        assertThat(stopped.terminalExit().orderId()).isEqualTo("ord-stop");

        AdoptionCandidates tookProfit = AdoptionCandidates.classify(List.of(
                fill("ord-entry", "buy", "limit", "2026-09-08T14:00:00Z"),
                row("ord-tp", "", "limit", "finalfill", "history", "take_profit",
                        "10", "120", null, "2026-09-09T14:00:00Z")), "buy");
        assertThat(tookProfit.terminalExit().orderId()).isEqualTo("ord-tp");
    }

    @Test void theStopLegNeedsOpenLiveAndAStopPrice() {
        // A history "changed stopiftraded" row is not open, so it is not a bindable leg.
        assertThat(AdoptionCandidates.classify(List.of(
                row("ord-hist", "sell", "stopiftraded", "changed", "history", "other",
                        null, null, "90", null)), "buy").stopLeg()).isNull();

        // Open and live but no stopPrice -> not bindable, and therefore unclaimed.
        AdoptionCandidates noPrice = AdoptionCandidates.classify(List.of(
                row("ord-nopx", "sell", "stopiftraded", "working", "open", "other",
                        null, null, null, null)), "buy");
        assertThat(noPrice.stopLeg()).isNull();
        assertThat(noPrice.unclaimedOpen()).extracting(BrokerOrder::orderId)
                .containsExactly("ord-nopx");

        // Blank side is allowed for the refusing classifications.
        assertThat(AdoptionCandidates.classify(List.of(
                row("ord-blank", "", "stopiftraded", "working", "open", "other",
                        null, null, "90", null)), "buy").stopLeg().orderId())
                .isEqualTo("ord-blank");
    }

    @Test void aWrongNonBlankSideIsRejectedEverywhere() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                openRow("ord-working", "sell", "limit", "working"),
                fill("ord-fill", "sell", "limit", "2026-09-08T14:00:00Z"),
                row("ord-stop", "buy", "stopiftraded", "working", "open", "other",
                        null, null, "90", null)), "buy");

        assertThat(c.working()).isNull();
        assertThat(c.filledEntry()).isNull();
        assertThat(c.filledUnverifiable()).isNull();
        assertThat(c.stopLeg()).isNull();          // wrong side for the exit
        assertThat(c.terminalExit().orderId()).isEqualTo("ord-fill");
    }

    @Test void roleOtherWithAStopTypeIsAStop() {
        assertThat(AdoptionCandidates.isStop(
                row("ord-stop", "sell", "stopiftraded", "working", "open", "other",
                        null, null, "90", null))).isTrue();
        assertThat(AdoptionCandidates.isStop(
                row("ord-entry", "buy", "limit", "working", "open", "other",
                        null, null, null, null))).isFalse();
    }

    @Test void isStopRoleClauseFiresEvenWithANonStopType() {
        // role STOP_LOSS with a type that is not in the stop-type set: the role clause alone
        // must be enough.
        assertThat(AdoptionCandidates.isStop(
                row("ord-stop-role", "sell", "limit", "working", "open", "stop_loss",
                        null, null, "90", null))).isTrue();
    }

    @Test void isStopTypeClauseFiresEvenWithRoleOther() {
        // role OTHER with a stop-shaped type: the type clause alone must be enough, and both
        // recognised stop type strings must be covered individually.
        assertThat(AdoptionCandidates.isStop(
                row("ord-stopiftraded", "sell", "stopiftraded", "working", "open", "other",
                        null, null, "90", null))).isTrue();
        assertThat(AdoptionCandidates.isStop(
                row("ord-stop-type", "sell", "stop", "working", "open", "other",
                        null, null, "90", null))).isTrue();
        assertThat(AdoptionCandidates.isStop(
                row("ord-limit", "sell", "limit", "working", "open", "other",
                        null, null, null, null))).isFalse();
    }

    @Test void unclaimedOpenCatchesAnOpenRowWithAnUnknownRawStatus() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                row("ord-weird", "buy", "limit", "teleported", "open", "other",
                        null, null, null, null)), "buy");

        assertThat(c.working()).isNull();          // rawStatus is not in LIVE_RAW
        assertThat(c.unclaimedOpen()).extracting(BrokerOrder::orderId)
                .containsExactly("ord-weird");
    }

    @Test void theChosenWorkingAndStopLegAreNotAlsoUnclaimed() {
        AdoptionCandidates c = AdoptionCandidates.classify(List.of(
                openRow("ord-working", "buy", "limit", "working"),
                row("ord-stop", "sell", "stopiftraded", "working", "open", "other",
                        null, null, "90", null)), "buy");

        assertThat(c.unclaimedOpen()).isEmpty();
    }

    /**
     * Anchors the private {@link #roleFor}/{@link #statusFor} mirrors above to the REAL mapping.
     * Every other test here builds its {@link BrokerOrder}s by hand; nothing proved that the
     * gateway can actually produce such a row, so a mapping change would leave ~25 tests green on
     * impossible fixtures.
     *
     * <p>The two JSON fixtures are shaped like the live wire (E2/E3): an OPEN protective leg
     * reported as {@code role "other"}, {@code type "stopiftraded"}, {@code status "working"}, and
     * a history fill reported as {@code status "finalfill"} -- the two rows the whole guard has to
     * tell apart. They go through {@code AgoraExecutionGateway.orders}/{@code filledOrdersSince},
     * i.e. through {@code toBrokerOrder} -> {@code roleOf}/{@code toStatus}, and the resulting
     * orders are classified.
     */
    @Test
    void theGatewaysOwnMappingProducesTheStopLegAndFillThisSuiteAssumes() {
        ScriptedGateway gw = new ScriptedGateway();
        gw.openOrders = """
                {"output":{"orders":[
                  {"brokerOrderId":"ord-stop","clientRef":"sig-1","symbol":"ACME","side":"Sell",
                   "qty":"10","type":"StopIfTraded","status":"Working","role":"other",
                   "stopPrice":"90"}
                ]}}
                """;
        gw.historyOrders = """
                {"output":{"orders":[
                  {"brokerOrderId":"ord-fill","clientRef":"sig-1","symbol":"ACME","side":"Buy",
                   "qty":"10","type":"Limit","status":"FinalFill","role":"other",
                   "filledQty":"10","avgFillPrice":"98.50","filledAt":"2026-09-08T14:00:00Z"}
                ]}}
                """;

        List<BrokerOrder> matches = new java.util.ArrayList<>(gw.orders("depot-1"));
        matches.addAll(gw.filledOrdersSince("depot-1", Instant.parse("2026-09-01T00:00:00Z")));

        // The mapping the mirrors claim: "other" + stopiftraded -> STOP_LOSS, finalfill -> FILLED,
        // side/type/rawStatus carried lower-cased, source tagged by the read.
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).role()).isEqualTo(OrderRole.STOP_LOSS);
        assertThat(matches.get(0).status()).isEqualTo(OrderStatus.WORKING);
        assertThat(matches.get(0).rawStatus()).isEqualTo("working");
        assertThat(matches.get(0).type()).isEqualTo("stopiftraded");
        assertThat(matches.get(0).side()).isEqualTo("sell");
        assertThat(matches.get(0).source()).isEqualTo("open");
        assertThat(matches.get(1).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(matches.get(1).source()).isEqualTo("history");

        AdoptionCandidates c = AdoptionCandidates.classify(matches, "buy");

        assertThat(c.stopLeg().orderId()).isEqualTo("ord-stop");
        assertThat(c.filledEntry().orderId()).isEqualTo("ord-fill");
        assertThat(c.working()).isNull();
        assertThat(c.terminalExit()).isNull();
        assertThat(c.unclaimedOpen()).isEmpty();
    }

    /** The real gateway with its HTTP seam stubbed: {@code get_orders} answers from
     *  {@link #openOrders} or {@link #historyOrders}, depending on the {@code status} argument the
     *  gateway itself sends. */
    private static class ScriptedGateway extends de.visterion.dracul.executor.broker.AgoraExecutionGateway {
        String openOrders = "{\"output\":{\"orders\":[]}}";
        String historyOrders = "{\"output\":{\"orders\":[]}}";
        private final tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();

        ScriptedGateway() {
            super("http://x", "tkn", new tools.jackson.databind.ObjectMapper(), 8000);
        }

        @Override
        protected tools.jackson.databind.JsonNode call(String tool, tools.jackson.databind.JsonNode args) {
            return om.readTree("closed".equals(args.path("status").asString(""))
                    ? historyOrders : openOrders);
        }
    }
}
