package de.visterion.dracul.strigoi.insider;

import de.visterion.dracul.hunting.edgar.Form4Filing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class InsiderClusterScreenerTest {

    private final InsiderClusterScreener screener = new InsiderClusterScreener();

    private static Form4Filing buy(String ticker, String filer, LocalDate d, long shares, long price) {
        return new Form4Filing(ticker, filer, "Director", d,
                BigDecimal.valueOf(shares), BigDecimal.valueOf(shares * price), "P");
    }

    @Test
    void detectsClusterOfThreeBuyersInThirtyDays() {
        var filings = List.of(
                buy("AAPL", "Alice", LocalDate.of(2026, 5, 1), 1000, 200),
                buy("AAPL", "Bob",   LocalDate.of(2026, 5, 15), 500, 200),
                buy("AAPL", "Carol", LocalDate.of(2026, 5, 28), 2000, 200)
        );
        var clusters = screener.cluster(filings);
        assertThat(clusters).hasSize(1);
        var c = clusters.get(0);
        assertThat(c.ticker()).isEqualTo("AAPL");
        assertThat(c.filers()).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        assertThat(c.totalDollarValue()).isEqualByComparingTo(new BigDecimal("700000"));
    }

    @Test
    void skipsSingleBuyer() {
        var filings = List.of(buy("AAPL", "Alice", LocalDate.of(2026,5,1), 5000, 200));
        assertThat(screener.cluster(filings)).isEmpty();
    }

    @Test
    void skipsTwoBuyersBelowThreshold() {
        var filings = List.of(
                buy("AAPL", "Alice", LocalDate.of(2026,5,1), 100, 200),
                buy("AAPL", "Bob",   LocalDate.of(2026,5,2), 100, 200)
        );
        assertThat(screener.cluster(filings)).isEmpty();
    }

    @Test
    void skipsClusterBelowDollarThreshold() {
        var filings = List.of(
                buy("AAPL", "A", LocalDate.of(2026,5,1), 100, 100),
                buy("AAPL", "B", LocalDate.of(2026,5,2), 100, 100),
                buy("AAPL", "C", LocalDate.of(2026,5,3), 100, 100)
        );
        assertThat(screener.cluster(filings)).isEmpty();
    }

    @Test
    void skipsSaleTransactions() {
        var filings = List.of(
                new Form4Filing("AAPL", "A", "Dir", LocalDate.of(2026,5,1), BigDecimal.valueOf(10000), BigDecimal.valueOf(2_000_000), "S"),
                new Form4Filing("AAPL", "B", "Dir", LocalDate.of(2026,5,2), BigDecimal.valueOf(10000), BigDecimal.valueOf(2_000_000), "S"),
                new Form4Filing("AAPL", "C", "Dir", LocalDate.of(2026,5,3), BigDecimal.valueOf(10000), BigDecimal.valueOf(2_000_000), "S")
        );
        assertThat(screener.cluster(filings)).isEmpty();
    }

    @Test
    void respectsThirtyDayWindow() {
        var filings = List.of(
                buy("AAPL", "A", LocalDate.of(2026,4,1), 1000, 200),
                buy("AAPL", "B", LocalDate.of(2026,4,15), 1000, 200),
                buy("AAPL", "C", LocalDate.of(2026,5,15), 1000, 200)
        );
        // A, B within 30 days = 2 buyers; B, C within 30 days = 2 buyers — no cluster
        assertThat(screener.cluster(filings)).isEmpty();
    }

    @Test
    void dedupesFilerNamesWithinSameCluster() {
        var filings = List.of(
                buy("AAPL", "Alice", LocalDate.of(2026,5,1), 1000, 200),
                buy("AAPL", "Alice", LocalDate.of(2026,5,5), 1000, 200),
                buy("AAPL", "Alice", LocalDate.of(2026,5,10), 1000, 200),
                buy("AAPL", "Bob",   LocalDate.of(2026,5,15), 1000, 200),
                buy("AAPL", "Carol", LocalDate.of(2026,5,20), 1000, 200)
        );
        var clusters = screener.cluster(filings);
        assertThat(clusters).hasSize(1);
        assertThat(clusters.get(0).filers()).containsExactlyInAnyOrder("Alice", "Bob", "Carol");
    }

    @Test
    void detectsClustersInDifferentTickersIndependently() {
        var filings = List.of(
                buy("AAPL", "A1", LocalDate.of(2026,5,1), 1000, 200),
                buy("AAPL", "A2", LocalDate.of(2026,5,5), 1000, 200),
                buy("AAPL", "A3", LocalDate.of(2026,5,10), 1000, 200),
                buy("MSFT", "M1", LocalDate.of(2026,5,1), 1000, 300),
                buy("MSFT", "M2", LocalDate.of(2026,5,5), 1000, 300),
                buy("MSFT", "M3", LocalDate.of(2026,5,10), 1000, 300)
        );
        var clusters = screener.cluster(filings);
        assertThat(clusters).extracting(InsiderCluster::ticker).containsExactlyInAnyOrder("AAPL", "MSFT");
    }
}
