package de.visterion.dracul.benchmark;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads prey + screen_candidate for event building; owns benchmark_outcome and
 *  benchmark_symbol_no_data (V55). */
@Repository
public class BenchmarkOutcomeRepository {

    private final JdbcClient jdbc;

    public BenchmarkOutcomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PreyRow> findPrey() {
        return jdbc.sql("""
                SELECT discovered_by, upper(btrim(symbol)) AS symbol, discovered_at
                  FROM prey
                 WHERE btrim(symbol) <> ''""")
                .query((rs, i) -> new PreyRow(rs.getString("discovered_by"), rs.getString("symbol"),
                        rs.getTimestamp("discovered_at").toInstant()))
                .list();
    }

    public List<CandidateRow> findCandidates() {
        return jdbc.sql("""
                SELECT agent, symbol, fetched_at, as_of_date,
                       extra->>'offerPrice' AS offer_price, extra->>'offerCurrency' AS offer_currency
                  FROM screen_candidate""")
                .query((rs, i) -> new CandidateRow(rs.getString("agent"), rs.getString("symbol"),
                        rs.getTimestamp("fetched_at").toInstant(), rs.getObject("as_of_date", LocalDate.class),
                        decimalOrNull(rs.getString("offer_price")), rs.getString("offer_currency")))
                .list();
    }

    public static String eventKey(EventKind kind, String agent, String symbol, LocalDate eventStart, int horizon) {
        return kind.name() + "|" + agent + "|" + symbol + "|" + eventStart + "|" + horizon;
    }

    /** One key per already written (kind, agent, symbol, event_start_date, horizon) — plan ruling R5. */
    public Set<String> completedEventKeys() {
        return new HashSet<>(jdbc.sql("""
                SELECT kind, agent, symbol, event_start_date, horizon_days FROM benchmark_outcome""")
                .query((rs, i) -> eventKey(EventKind.valueOf(rs.getString("kind")), rs.getString("agent"),
                        rs.getString("symbol"), rs.getObject("event_start_date", LocalDate.class),
                        rs.getInt("horizon_days")))
                .list());
    }

    /** @return true when written; false when the unique key already existed. */
    public boolean insert(BenchmarkOutcome o) {
        return jdbc.sql("""
                INSERT INTO benchmark_outcome (kind, agent, symbol, event_start_date, entry_ref_date, entry_ref_at,
                       picked, horizon_days, entry_date, entry_price, exit_date, exit_price, currency,
                       fx_entry, fx_exit, return_eur, benchmark_return_eur, excess, exit_kind)
                VALUES (:kind, :agent, :symbol, :eventStart, :refDate, :refAt, :picked, :horizon, :entryDate,
                        :entryPrice, :exitDate, :exitPrice, :currency, :fxEntry, :fxExit, :returnEur,
                        :benchmark, :excess, :exitKind)
                ON CONFLICT (kind, agent, symbol, entry_ref_date, horizon_days) DO NOTHING""")
                .param("kind", o.kind().name())
                .param("agent", o.agent())
                .param("symbol", o.symbol())
                .param("eventStart", o.eventStartDate())
                .param("refDate", o.entryRefDate())
                .param("refAt", Timestamp.from(o.entryRefAt()))
                .param("picked", o.picked())
                .param("horizon", o.horizonDays())
                .param("entryDate", o.entryDate())
                .param("entryPrice", o.entryPrice())
                .param("exitDate", o.exitDate())
                .param("exitPrice", o.exitPrice())
                .param("currency", o.currency())
                .param("fxEntry", o.fxEntry())
                .param("fxExit", o.fxExit())
                .param("returnEur", o.returnEur())
                .param("benchmark", o.benchmarkReturnEur())
                .param("excess", o.excess())
                .param("exitKind", o.exitKind().name())
                .update() == 1;
    }

    public int noDataNights(String symbol) {
        return jdbc.sql("SELECT nights FROM benchmark_symbol_no_data WHERE symbol = :s")
                .param("s", symbol).query(Integer.class).optional().orElse(0);
    }

    /** Counts at most once per UTC night (the WHERE on the conflict branch). */
    public void recordNoData(String symbol, LocalDate night) {
        jdbc.sql("""
                INSERT INTO benchmark_symbol_no_data (symbol, nights, last_night) VALUES (:s, 1, :night)
                ON CONFLICT (symbol) DO UPDATE
                   SET nights = benchmark_symbol_no_data.nights + 1, last_night = EXCLUDED.last_night
                 WHERE benchmark_symbol_no_data.last_night < EXCLUDED.last_night""")
                .param("s", symbol).param("night", night).update();
    }

    public void clearNoData(String symbol) {
        jdbc.sql("DELETE FROM benchmark_symbol_no_data WHERE symbol = :s").param("s", symbol).update();
    }

    /** Measured rows only — NO_ENTRY has no excess and never enters a mean. */
    public List<StatRow> findForStats() {
        return jdbc.sql("""
                SELECT kind, agent, picked, horizon_days, entry_date, excess
                  FROM benchmark_outcome
                 WHERE exit_kind <> 'NO_ENTRY' AND excess IS NOT NULL AND entry_date IS NOT NULL""")
                .query((rs, i) -> new StatRow(EventKind.valueOf(rs.getString("kind")), rs.getString("agent"),
                        rs.getObject("picked", Boolean.class), rs.getInt("horizon_days"),
                        rs.getObject("entry_date", LocalDate.class), rs.getBigDecimal("excess")))
                .list();
    }

    private static BigDecimal decimalOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
