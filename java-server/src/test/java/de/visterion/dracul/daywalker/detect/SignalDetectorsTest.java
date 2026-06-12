package de.visterion.dracul.daywalker.detect;

import de.visterion.dracul.hunting.edgar.Form4Filing;
import de.visterion.dracul.hunting.finnhub.NewsHeadline;
import de.visterion.dracul.hunting.finnhub.RecommendationTrend;
import de.visterion.dracul.watchlist.WatchlistItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalDetectorsTest {

    private static WatchlistItem item() {
        return new WatchlistItem("id-1", "ACME", "Acme Corp", 100.0, 0.0,
                "calm", "2026-06-01", "", null, List.of(), List.of(), null, null, null);
    }

    private static Form4Filing filing(String ticker, String code) {
        return new Form4Filing(ticker, "Jane Insider", "CFO", LocalDate.of(2026, 6, 2),
                new BigDecimal("1000"), new BigDecimal("120000"), code);
    }

    @Test
    void insiderSellFiresOnSaleForTicker() {
        var d = new InsiderSellDetector();
        var ev = d.detect(item(), List.of(filing("ACME", "S")));
        assertThat(ev).isPresent();
        assertThat(ev.get().triggerType()).isEqualTo(TriggerType.INSIDER_SELL);
    }

    @Test
    void insiderSellIgnoresPurchaseAndOtherTickers() {
        var d = new InsiderSellDetector();
        assertThat(d.detect(item(), List.of(filing("ACME", "P")))).isEmpty();
        assertThat(d.detect(item(), List.of(filing("OTHR", "S")))).isEmpty();
    }

    @Test
    void newsFiresWhenHeadlinePresent() {
        var d = new NewsDetector();
        var ev = d.detect(item(), List.of(new NewsHeadline(
                "Acme cuts guidance", "Q2 miss", "Reuters", Instant.now(), "http://n/1")));
        assertThat(ev).isPresent();
        assertThat(ev.get().triggerType()).isEqualTo(TriggerType.NEGATIVE_NEWS);
    }

    @Test
    void newsEmptyWhenNoHeadlines() {
        assertThat(new NewsDetector().detect(item(), List.of())).isEmpty();
    }

    @Test
    void downgradeFiresWhenTrendShiftsToSell() {
        var d = new DowngradeDetector();
        var trends = List.of(
                new RecommendationTrend("2026-05", 1, 2, 3, 4, 1),  // more bearish (latest)
                new RecommendationTrend("2026-04", 3, 4, 2, 1, 0)); // less bearish (prior)
        assertThat(d.detect(item(), trends)).isPresent();
    }

    @Test
    void downgradeEmptyWhenTrendImproves() {
        var d = new DowngradeDetector();
        var trends = List.of(
                new RecommendationTrend("2026-05", 3, 4, 2, 1, 0),  // less bearish (latest)
                new RecommendationTrend("2026-04", 1, 2, 3, 4, 1)); // more bearish (prior)
        assertThat(d.detect(item(), trends)).isEmpty();
    }

    @Test
    void downgradeEmptyWithInsufficientHistory() {
        assertThat(new DowngradeDetector().detect(item(),
                List.of(new RecommendationTrend("2026-05", 1, 1, 1, 1, 1)))).isEmpty();
    }
}
