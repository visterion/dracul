package de.visterion.dracul.executor;

import de.visterion.dracul.executor.broker.BrokerOrder;
import de.visterion.dracul.executor.broker.OrderRole;
import de.visterion.dracul.executor.broker.OrderStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the broker reports under ONE clientRef, sorted into the roles the place-entry adoption guard
 * has to tell apart. Pure — no repository, no gateway, no clock.
 *
 * <p>A single clientRef routinely carries the bracket parent, its protective stop, its take-profit
 * and any dead earlier placement, and Saxo reports {@code role: "other"} on almost all of them. The
 * guard's whole safety rests on never confusing those: adopting the stop leg as the entry hands the
 * GTD expiry sweeper a licence to cancel the protection, and re-placing next to a live order doubles
 * the position.
 *
 * <p><b>Adopting classifications use {@link #strictSide}, refusing ones use {@link #looseSide}.</b>
 * A blank side (Saxo's embedded OCO children carry none) is unknown, and unknown must never be
 * enough to book something, but it must always be enough to refuse.
 */
public record AdoptionCandidates(
        /** The still-resting same-side entry order — today's adoption target (case A). */
        BrokerOrder working,
        /** The entry order that already filled and carries both fill fields (case B's candidate). */
        BrokerOrder filledEntry,
        /** A same-side non-stop fill missing filledQty or avgFillPrice — enough to know a trade
         *  happened, not enough to book one. */
        BrokerOrder filledUnverifiable,
        /** A fill on the exit side (or of unknown side): the thesis already left the book. */
        BrokerOrder terminalExit,
        /** The live protective stop leg, bindable because it carries a stopPrice. */
        BrokerOrder stopLeg,
        /** Everything else the broker still has open and non-terminal under this ref. Non-empty
         *  means something may still be working that no classification explains — never place
         *  next to it. */
        List<BrokerOrder> unclaimedOpen) {

    /** Raw broker statuses that really mean "resting at the broker right now". Deliberately NOT
     *  derived from {@link OrderStatus}: unrecognised statuses map to WORKING on purpose (a
     *  non-terminal guess can never fabricate a close), and that conservative mapping must not be
     *  read back as evidence of liveness. {@code notworking} is absent — it is an embedded OCO
     *  child copy, not a resting order. */
    static final Set<String> LIVE_RAW = Set.of("working", "open", "changed", "new", "accepted",
            "pending_new", "held", "partially_filled", "partial", "partialfill");

    private static final Set<String> STOP_TYPES = Set.of("stopiftraded", "stop");

    /** True while the row came out of the OPEN-orders view. A null {@code source} predates the
     *  tagging and is read as open — the conservative direction, since "open" only ever makes the
     *  guard refuse more. */
    public static boolean isOpen(BrokerOrder o) {
        return o.source() == null || "open".equals(o.source());
    }

    /** Both the mapped status AND the raw string have to say the order is resting. */
    public static boolean isLive(BrokerOrder o) {
        if (o.status() != OrderStatus.WORKING && o.status() != OrderStatus.PARTIALLY_FILLED) {
            return false;
        }
        return o.rawStatus() != null && LIVE_RAW.contains(o.rawStatus());
    }

    /** The wider net: open, and not known to be over. An open row with a status nobody mapped is
     *  caught here even though {@link #isLive} refuses it. */
    public static boolean isOpenNonTerminal(BrokerOrder o) {
        return isOpen(o)
                && o.status() != OrderStatus.FILLED
                && o.status() != OrderStatus.CANCELLED
                && o.status() != OrderStatus.REJECTED;
    }

    public static boolean isStop(BrokerOrder o) {
        return o.role() == OrderRole.STOP_LOSS
                || (o.type() != null && STOP_TYPES.contains(o.type()));
    }

    public static boolean isTakeProfit(BrokerOrder o) {
        return o.role() == OrderRole.TAKE_PROFIT;
    }

    /** The broker positively reported THIS side. A blank or null side is not a match. */
    public static boolean strictSide(BrokerOrder o, String side) {
        return o.side() != null && !o.side().isBlank() && o.side().equalsIgnoreCase(side);
    }

    /** THIS side, or no side at all. Used only where the answer makes the guard refuse. */
    public static boolean looseSide(BrokerOrder o, String side) {
        return o.side() == null || o.side().isBlank() || o.side().equalsIgnoreCase(side);
    }

    public static String oppositeSide(String side) {
        return "buy".equalsIgnoreCase(side) ? "sell" : "buy";
    }

    /** Earliest known fill first, unknown fill times last, order id as the deterministic tiebreak. */
    private static final Comparator<BrokerOrder> BY_FILL_TIME =
            Comparator.comparing(BrokerOrder::filledAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(o -> o.orderId() == null ? "" : o.orderId());

    /**
     * @param matches   every order the broker reports under the ref, open rows first
     *                  ({@code ExecutionGateway.ordersByRef})
     * @param entrySide the signal's side, lower-cased ({@code "buy"} / {@code "sell"})
     */
    public static AdoptionCandidates classify(List<BrokerOrder> matches, String entrySide) {
        String side = entrySide == null ? "" : entrySide.toLowerCase(Locale.ROOT);
        String exitSide = oppositeSide(side);

        BrokerOrder working = matches.stream()
                .filter(o -> isOpen(o) && isLive(o) && !isStop(o) && !isTakeProfit(o)
                        && strictSide(o, side))
                .findFirst().orElse(null);

        List<BrokerOrder> sameSideFills = matches.stream()
                .filter(o -> o.status() == OrderStatus.FILLED && !isStop(o) && !isTakeProfit(o)
                        && strictSide(o, side))
                .sorted(BY_FILL_TIME)
                .toList();

        BrokerOrder filledEntry = sameSideFills.stream()
                .filter(o -> o.filledQty() != null && o.avgFillPrice() != null)
                .findFirst().orElse(null);
        BrokerOrder filledUnverifiable = filledEntry != null ? null
                : sameSideFills.stream().findFirst().orElse(null);

        BrokerOrder chosenFill = filledEntry;
        BrokerOrder terminalExit = matches.stream()
                .filter(o -> o.status() == OrderStatus.FILLED)
                .filter(o -> chosenFill == null || !o.equals(chosenFill))
                .filter(o -> looseSide(o, exitSide))
                .findFirst().orElse(null);

        BrokerOrder stopLeg = matches.stream()
                .filter(o -> isOpen(o) && isLive(o) && isStop(o) && looseSide(o, exitSide)
                        && o.stopPrice() != null)
                .findFirst().orElse(null);

        List<BrokerOrder> unclaimedOpen = new ArrayList<>();
        for (BrokerOrder o : matches) {
            if (!isOpenNonTerminal(o)) continue;
            if (o.equals(working) || o.equals(stopLeg)) continue;
            unclaimedOpen.add(o);
        }

        return new AdoptionCandidates(working, filledEntry, filledUnverifiable, terminalExit,
                stopLeg, List.copyOf(unclaimedOpen));
    }
}
