package de.visterion.dracul.benchmark;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Spec §2.6 — two separate measurements, never mixed:
 * <ol>
 *   <li>SIGNAL events = prey. One per (agent, symbol) first discovered_at; a later prey starts a
 *       new event only more than 60 EUNL trading days after the current event's start.</li>
 *   <li>CANDIDATE events = consecutive appearances of (agent, symbol); a gap of more than 60 EUNL
 *       trading days starts a new one. Picked iff a prey of the same agent and symbol falls into
 *       [first appearance − 60 trading days, last appearance + 5 calendar days]; a prey picks at
 *       most one event (earliest first). Picked events are measured from the prey, rejected ones
 *       from their first appearance.</li>
 * </ol>
 */
public final class EventBuilder {

    public static final int GAP_TRADING_DAYS = 60;
    public static final int PICK_BEFORE_TRADING_DAYS = 60;
    public static final int PICK_AFTER_CALENDAR_DAYS = 5;

    private EventBuilder() {
    }

    public static String key(String agent, String symbol) {
        return agent + "|" + symbol;
    }

    /** Most recent non-null merger offer per (agent, symbol). */
    public static Map<String, Offer> latestOffers(List<CandidateRow> candidates) {
        Map<String, CandidateRow> latest = new HashMap<>();
        for (CandidateRow c : candidates) {
            if (c.offerPrice() == null) continue;
            latest.merge(key(c.agent(), c.symbol()), c, (a, b) -> b.fetchedAt().isAfter(a.fetchedAt()) ? b : a);
        }
        Map<String, Offer> out = new HashMap<>();
        latest.forEach((k, c) -> out.put(k, new Offer(c.offerPrice(), c.offerCurrency())));
        return out;
    }

    public static List<MeasureEvent> signalEvents(List<PreyRow> prey, TradingCalendar cal, Map<String, Offer> offers) {
        Map<String, List<PreyRow>> byKey = new TreeMap<>();
        for (PreyRow p : prey) byKey.computeIfAbsent(key(p.agent(), p.symbol()), k -> new ArrayList<>()).add(p);
        List<MeasureEvent> out = new ArrayList<>();
        for (Map.Entry<String, List<PreyRow>> entry : byKey.entrySet()) {
            List<PreyRow> rows = new ArrayList<>(entry.getValue());
            rows.sort(Comparator.comparing(PreyRow::discoveredAt));
            LocalDate start = null;
            for (PreyRow p : rows) {
                if (start == null || cal.countBetween(start, p.date()) > GAP_TRADING_DAYS) {
                    start = p.date();
                    out.add(new MeasureEvent(EventKind.SIGNAL, p.agent(), p.symbol(), start, p.discoveredAt(),
                            null, start, offers.get(entry.getKey())));
                }
            }
        }
        out.sort(Comparator.comparing(MeasureEvent::refAt));
        return out;
    }

    public static List<MeasureEvent> candidateEvents(List<CandidateRow> candidates, List<PreyRow> prey,
                                                     TradingCalendar cal) {
        Map<String, Offer> offers = latestOffers(candidates);
        Map<String, List<CandidateRow>> byKey = new TreeMap<>();
        for (CandidateRow c : candidates) byKey.computeIfAbsent(key(c.agent(), c.symbol()), k -> new ArrayList<>()).add(c);
        Map<String, List<PreyRow>> preyByKey = new HashMap<>();
        for (PreyRow p : prey) preyByKey.computeIfAbsent(key(p.agent(), p.symbol()), k -> new ArrayList<>()).add(p);
        preyByKey.values().forEach(l -> l.sort(Comparator.comparing(PreyRow::discoveredAt)));

        Set<PreyRow> consumed = new HashSet<>();
        List<MeasureEvent> out = new ArrayList<>();
        for (Map.Entry<String, List<CandidateRow>> entry : byKey.entrySet()) {
            List<CandidateRow> rows = new ArrayList<>(entry.getValue());
            rows.sort(Comparator.comparing(CandidateRow::fetchedAt));
            List<List<CandidateRow>> groups = new ArrayList<>();
            List<CandidateRow> current = null;
            LocalDate prev = null;
            for (CandidateRow c : rows) {
                if (current == null || cal.countBetween(prev, c.asOfDate()) > GAP_TRADING_DAYS) {
                    current = new ArrayList<>();
                    groups.add(current);
                }
                current.add(c);
                prev = c.asOfDate();
            }
            for (List<CandidateRow> g : groups) {
                CandidateRow first = g.getFirst();
                LocalDate firstDay = first.asOfDate();
                LocalDate lastDay = g.getLast().asOfDate();
                LocalDate from = cal.minus(firstDay, PICK_BEFORE_TRADING_DAYS);
                LocalDate to = lastDay.plusDays(PICK_AFTER_CALENDAR_DAYS);
                PreyRow pick = null;
                for (PreyRow p : preyByKey.getOrDefault(entry.getKey(), List.of())) {
                    if (consumed.contains(p)) continue;
                    if (!p.date().isBefore(from) && !p.date().isAfter(to)) {
                        pick = p;
                        break;
                    }
                }
                if (pick != null) consumed.add(pick);
                out.add(new MeasureEvent(EventKind.CANDIDATE, first.agent(), first.symbol(), firstDay,
                        pick != null ? pick.discoveredAt() : first.fetchedAt(), pick != null, lastDay,
                        offers.get(entry.getKey())));
            }
        }
        out.sort(Comparator.comparing(MeasureEvent::refAt));
        return out;
    }
}
