package de.visterion.dracul.executor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory {@link SavingsPlanRepository} for unit tests: the same CAS / first-writer / ON
 *  CONFLICT semantics as the SQL, no Postgres. {@link #now} stamps created_at / updated_at and is
 *  the lease clock. Every public repository method is overridden — a method added to the repository
 *  later must be added here in the same task. */
class InMemorySavingsPlanRepository extends SavingsPlanRepository {

    static final class Row {
        Long id; String month; long positionId; String symbol; BigDecimal qty; BigDecimal limitPrice;
        BigDecimal limitEur; String clientRef; String entryOrderId; String childStopOrderId;
        BigDecimal qtyBefore; BigDecimal avgBefore; BigDecimal stopBefore; String status;
        String newStopOrderId; String skipReason; BigDecimal windowStopQty; BigDecimal targetQty;
        BigDecimal targetStop; BigDecimal fillQty; BigDecimal fillPrice; BigDecimal avgAfter;
        BigDecimal stopAfter; String tif; Instant createdAt; Instant updatedAt;

        SavingsBuy toBuy() {
            return new SavingsBuy(id, month, positionId, symbol, qty, limitPrice, limitEur, clientRef,
                    entryOrderId, childStopOrderId, qtyBefore, avgBefore, stopBefore, status,
                    newStopOrderId, skipReason, windowStopQty, targetQty, targetStop, fillQty,
                    fillPrice, avgAfter, stopAfter, tif, createdAt, updatedAt);
        }
    }

    final Map<Long, Row> rows = new LinkedHashMap<>();
    final Map<String, SavingsMonth> months = new HashMap<>();
    final Map<Long, BigDecimal> carry = new HashMap<>();
    /** Positions {@link #deleteStaleCarry} treats as no longer OPEN / half-sold. */
    final Set<Long> staleCarryPositions = new HashSet<>();
    Instant now = Instant.parse("2026-11-02T23:00:00Z");
    String leaseHolder;
    Instant leaseUntil = Instant.MIN;
    private long nextId = 1;

    InMemorySavingsPlanRepository() {
        super(null);
    }

    /** Test seeding: one row in any status. */
    Row seed(String month, long positionId, String symbol, String status, String qty, String limit,
            String limitEur, String qtyBefore, String avgBefore, String stopBefore, Instant createdAt) {
        Row r = new Row();
        r.id = nextId++;
        r.month = month; r.positionId = positionId; r.symbol = symbol; r.status = status;
        r.qty = new BigDecimal(qty);
        r.limitPrice = limit == null ? null : new BigDecimal(limit);
        r.limitEur = limitEur == null ? null : new BigDecimal(limitEur);
        r.clientRef = SavingsBuy.clientRef(positionId, month);
        r.qtyBefore = new BigDecimal(qtyBefore); r.avgBefore = new BigDecimal(avgBefore);
        r.stopBefore = new BigDecimal(stopBefore); r.tif = "gtc";
        r.createdAt = createdAt; r.updatedAt = createdAt;
        rows.put(r.id, r);
        return r;
    }

    private Row row(long id) { return rows.get(id); }
    private void touch(Row r) { r.updatedAt = now; }

    @Override public boolean tryAcquireLease(String pass) {
        if (!leaseUntil.isBefore(now)) return false;
        leaseHolder = pass;
        leaseUntil = now.plusSeconds(600);
        return true;
    }
    @Override public boolean renewLease(String pass) {
        if (!pass.equals(leaseHolder)) return false;
        leaseUntil = now.plusSeconds(600);
        return true;
    }
    @Override public void releaseLease(String pass) {
        if (pass.equals(leaseHolder)) leaseUntil = Instant.MIN;
    }
    @Override public String leaseHolder() {
        return leaseUntil.isAfter(now) ? leaseHolder : null;
    }

    @Override public SavingsMonth findMonth(String month) { return months.get(month); }
    @Override public SavingsMonth ensureMonth(String month, BigDecimal amountEur, int candidateCount) {
        SavingsMonth m = months.get(month);
        if (m == null) {
            months.put(month, new SavingsMonth(month, amountEur, candidateCount, null, null));
        } else {
            months.put(month, new SavingsMonth(month,
                    m.monthAmountEur() != null ? m.monthAmountEur() : amountEur,
                    m.candidateCount() != null ? m.candidateCount() : Integer.valueOf(candidateCount),
                    m.completedAt(), m.missedAlertedAt()));
        }
        return months.get(month);
    }
    @Override public boolean completeMonth(String month) {
        SavingsMonth m = months.get(month);
        if (m != null && m.completedAt() != null) return false;
        months.put(month, m == null ? new SavingsMonth(month, null, null, now, null)
                : new SavingsMonth(month, m.monthAmountEur(), m.candidateCount(), now, m.missedAlertedAt()));
        return true;
    }
    @Override public boolean markMissedAlerted(String month) {
        SavingsMonth m = months.get(month);
        if (m != null && m.missedAlertedAt() != null) return false;
        months.put(month, m == null ? new SavingsMonth(month, null, null, null, now)
                : new SavingsMonth(month, m.monthAmountEur(), m.candidateCount(), m.completedAt(), now));
        return true;
    }

    @Override public BigDecimal carryOf(long positionId) { return carry.getOrDefault(positionId, BigDecimal.ZERO); }
    @Override public void setCarry(long positionId, BigDecimal carryEur) { carry.put(positionId, carryEur); }
    @Override public void addCarry(long positionId, BigDecimal deltaEur) { carry.merge(positionId, deltaEur, BigDecimal::add); }
    @Override public void deleteCarry(long positionId) { carry.remove(positionId); }
    @Override public int deleteStaleCarry() {
        int n = 0;
        for (Long id : staleCarryPositions) if (carry.remove(id) != null) n++;
        return n;
    }

    private boolean conflict(String month, long positionId) {
        String ref = SavingsBuy.clientRef(positionId, month);
        return rows.values().stream().anyMatch(r -> (r.month.equals(month) && r.positionId == positionId)
                || r.clientRef.equals(ref));
    }

    @Override public Long insertPlacing(String month, long positionId, String symbol, BigDecimal qty,
            BigDecimal limitPrice, BigDecimal limitEur, BigDecimal qtyBefore, BigDecimal avgBefore,
            BigDecimal stopBefore, String tif) {
        if (conflict(month, positionId)) return null;
        Row r = new Row();
        r.id = nextId++; r.month = month; r.positionId = positionId; r.symbol = symbol; r.qty = qty;
        r.limitPrice = limitPrice; r.limitEur = limitEur; r.clientRef = SavingsBuy.clientRef(positionId, month);
        r.qtyBefore = qtyBefore; r.avgBefore = avgBefore; r.stopBefore = stopBefore;
        r.status = SavingsBuy.PLACING; r.tif = tif; r.createdAt = now; r.updatedAt = now;
        rows.put(r.id, r);
        return r.id;
    }
    @Override public Long insertSkipped(String month, long positionId, String symbol, BigDecimal qtyBefore,
            BigDecimal avgBefore, BigDecimal stopBefore, String reason) {
        if (conflict(month, positionId)) return null;
        Row r = new Row();
        r.id = nextId++; r.month = month; r.positionId = positionId; r.symbol = symbol;
        r.qty = BigDecimal.ZERO; r.clientRef = SavingsBuy.clientRef(positionId, month);
        r.qtyBefore = qtyBefore; r.avgBefore = avgBefore; r.stopBefore = stopBefore;
        r.status = SavingsBuy.SKIPPED; r.skipReason = reason; r.createdAt = now; r.updatedAt = now;
        rows.put(r.id, r);
        return r.id;
    }
    @Override public boolean existsForMonth(String month, long positionId) {
        return rows.values().stream().anyMatch(r -> r.month.equals(month) && r.positionId == positionId);
    }
    @Override public SavingsBuy findById(long id) { Row r = row(id); return r == null ? null : r.toBuy(); }
    @Override public List<SavingsBuy> findInFlight() {
        List<SavingsBuy> out = new ArrayList<>();
        rows.values().stream().filter(r -> SavingsBuy.IN_FLIGHT.contains(r.status))
                .sorted(Comparator.comparing(r -> r.id)).forEach(r -> out.add(r.toBuy()));
        return out;
    }
    @Override public SavingsBuy findInFlightByPosition(long positionId) {
        return findInFlight().stream().filter(b -> b.positionId() == positionId).findFirst().orElse(null);
    }
    @Override public Map<Long, String> inFlightStatusByPosition() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (SavingsBuy b : findInFlight()) out.putIfAbsent(b.positionId(), b.status());
        return out;
    }
    @Override public Set<Long> positionsTouchedSince(Instant since) {
        Set<Long> out = new HashSet<>();
        for (Row r : rows.values()) if (!r.updatedAt.isBefore(since)) out.add(r.positionId);
        return out;
    }
    @Override public List<SavingsBuy> findByMonth(String month) {
        return rows.values().stream().filter(r -> r.month.equals(month)).map(Row::toBuy).toList();
    }

    @Override public boolean markPlaced(long id, String entryOrderId, String childStopOrderId) {
        Row r = row(id);
        if (r == null || !SavingsBuy.PLACING.equals(r.status)) return false;
        r.status = SavingsBuy.PLACED; r.entryOrderId = entryOrderId; r.childStopOrderId = childStopOrderId;
        touch(r);
        return true;
    }
    @Override public boolean transition(long id, String expected, String next) {
        Row r = row(id);
        if (r == null || !expected.equals(r.status)) return false;
        r.status = next;
        touch(r);
        return true;
    }
    @Override public boolean markConsolidating(long id, String expected, BigDecimal targetQty,
            BigDecimal targetStop, BigDecimal avgAfter) {
        Row r = row(id);
        if (r == null || !expected.equals(r.status)) return false;
        if (!SavingsBuy.UNPROTECTED.equals(r.status)) r.status = SavingsBuy.CONSOLIDATING;
        r.targetQty = targetQty; r.targetStop = targetStop; r.avgAfter = avgAfter;
        touch(r);
        return true;
    }
    @Override public boolean setNewStopOrderId(long id, String expected, String newStopOrderId) {
        Row r = row(id);
        if (r == null || !expected.equals(r.status)) return false;
        r.newStopOrderId = newStopOrderId;
        touch(r);
        return true;
    }
    @Override public boolean markWindowStop(long id, BigDecimal qty) {
        Row r = row(id);
        if (r == null || r.windowStopQty != null) return false;
        r.windowStopQty = qty;
        touch(r);
        return true;
    }
    @Override public boolean markEmergencyExit(long id, String expected, BigDecimal fillQty, BigDecimal fillPrice) {
        Row r = row(id);
        if (r == null || !expected.equals(r.status)) return false;
        r.status = SavingsBuy.EMERGENCY_EXIT; r.fillQty = fillQty; r.fillPrice = fillPrice;
        touch(r);
        return true;
    }
    @Override public boolean finish(long id, String expected, String terminal, BigDecimal fillQty,
            BigDecimal fillPrice, BigDecimal avgAfter, BigDecimal stopAfter) {
        Row r = row(id);
        if (r == null || !expected.equals(r.status)) return false;
        r.status = terminal;
        if (fillQty != null) r.fillQty = fillQty;
        if (fillPrice != null) r.fillPrice = fillPrice;
        if (avgAfter != null) r.avgAfter = avgAfter;
        if (stopAfter != null) r.stopAfter = stopAfter;
        touch(r);
        return true;
    }
}
