package de.visterion.dracul.daywalker.detect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.visterion.dracul.hunting.agora.Form4Filing;
import de.visterion.dracul.hunting.agora.NewsHeadline;
import de.visterion.dracul.hunting.agora.RecommendationTrend;
import de.visterion.dracul.watchlist.WatchlistItem;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalDetectorsTest {

    private static WatchlistItem item() {
        return new WatchlistItem("id-1", "ACME", "Acme Corp", 100.0, 0.0,
                "calm", "2026-06-01", "", null, List.of(), List.of(), null, null, null, null, null);
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
    void newsFiresWhenHeadlineTaggedAndCarriesEventTags() {
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(new NewsHeadline(
                "Acme cuts guidance", "Q2 miss", "Reuters", "news", Instant.now(), "http://n/1")));
        var ev = res.trigger();
        assertThat(ev).isPresent();
        assertThat(ev.get().triggerType()).isEqualTo(TriggerType.NEGATIVE_NEWS);
        assertThat(ev.get().detail())
                .containsEntry("headline", "Acme cuts guidance")
                .containsEntry("source", "Reuters")
                .containsEntry("url", "http://n/1")
                .containsEntry("event_tags", "guidance_cut");
    }

    @Test
    void newsEmptyWhenNoHeadlines() {
        assertThat(new NewsDetector().detect(item(), List.of()).trigger()).isEmpty();
    }

    @Test
    void newsFirstTaggedHeadlineWinsNotFirstOverall() {
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(
                new NewsHeadline("Acme wins award", "nice quarter", "Reuters", "news",
                        Instant.now(), "http://n/1"),
                new NewsHeadline("Acme announces public offering amid takeover talk", "",
                        "Reuters", "news", Instant.now(), "http://n/2")));
        var ev = res.trigger();
        assertThat(ev).isPresent();
        assertThat(ev.get().detail())
                .containsEntry("headline", "Acme announces public offering amid takeover talk")
                // within one headline: enum declaration order (MA before DILUTION)
                .containsEntry("event_tags", "ma,dilution");
    }

    @Test
    void newsSuppressedAtInfoWhenNoHeadlineTagged() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(NewsDetector.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var d = new NewsDetector();
            assertThat(d.detect(item(), List.of(
                    new NewsHeadline("Acme wins award", "nice quarter", "Reuters", "news",
                            Instant.now(), "http://n/1"),
                    new NewsHeadline("Acme opens new factory", "", "Reuters", "news",
                            Instant.now(), "http://n/2"))).trigger()).isEmpty();
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.INFO);
                assertThat(e.getFormattedMessage())
                        .isEqualTo("news: 2 untagged headlines suppressed for ACME");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void newsDetailMapIsNullSafe() {
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(new NewsHeadline(
                "Acme cuts guidance", null, null, "news", Instant.now(), null)));
        var ev = res.trigger();
        assertThat(ev).isPresent();
        assertThat(ev.get().detail())
                .containsEntry("headline", "Acme cuts guidance")
                .containsEntry("source", "")
                .containsEntry("url", "")
                .containsEntry("event_tags", "guidance_cut");
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

    @Test
    void macroOnlyHeadlineNeverFiresPerSymbolButIsCollected() {
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(new NewsHeadline(
                "Fed raises rates again", "", "Reuters", "news",
                Instant.parse("2026-06-03T12:00:00Z"), "http://n/1")));
        assertThat(res.trigger()).isEmpty();
        assertThat(res.macroOnly()).hasSize(1);
        var mh = res.macroOnly().get(0);
        assertThat(mh.headline()).isEqualTo("Fed raises rates again");
        assertThat(mh.sourceSymbol()).isEqualTo("ACME");
        assertThat(mh.datetime()).isEqualTo(Instant.parse("2026-06-03T12:00:00Z"));
        assertThat(mh.tags()).isEqualTo("macro");
    }

    @Test
    void mixedTagHeadlineStaysOnThePerSymbolPathOnly() {
        // MACRO + GUIDANCE_CUT: the specific tag dominates — per-symbol trigger, empty bucket.
        var d = new NewsDetector();
        // "tariffs" hits MACRO, "cuts guidance" hits GUIDANCE_CUT (plain contains matching)
        var res = d.detect(item(), List.of(new NewsHeadline(
                "Tariffs announced: Acme cuts guidance", "", "Reuters", "news",
                Instant.now(), "http://n/1")));
        assertThat(res.trigger()).isPresent();
        assertThat((String) res.trigger().get().detail().get("event_tags")).contains("guidance_cut");
        assertThat(res.macroOnly()).isEmpty();
    }

    @Test
    void macroFirstBatchStillFiresTheLaterSpecificHeadlinePerSymbol() {
        // Pins the m2 behavior change: pre-T2.2 the macro headline consumed the single slot.
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(
                new NewsHeadline("Fed raises rates again", "", "Reuters", "news",
                        Instant.now(), "http://n/1"),
                new NewsHeadline("Acme cuts guidance", "", "Reuters", "news",
                        Instant.now(), "http://n/2")));
        assertThat(res.trigger()).isPresent();
        assertThat(res.trigger().get().detail()).containsEntry("headline", "Acme cuts guidance");
        assertThat(res.macroOnly()).hasSize(1);
    }

    @Test
    void macroAfterSpecificInTheSameBatchStillEntersTheBucket() {
        var d = new NewsDetector();
        var res = d.detect(item(), List.of(
                new NewsHeadline("Acme cuts guidance", "", "Reuters", "news",
                        Instant.now(), "http://n/1"),
                new NewsHeadline("Recession fears grip markets", "", "Reuters", "news",
                        Instant.now(), "http://n/2")));
        assertThat(res.trigger()).isPresent();
        assertThat(res.trigger().get().detail()).containsEntry("headline", "Acme cuts guidance");
        assertThat(res.macroOnly()).hasSize(1);
    }

    @Test
    void newsDetectorDetailCarriesCredibility() {
        var d = new NewsDetector();
        var h = new NewsHeadline("Acme cuts guidance", "s", "Reuters", "news",
                Instant.parse("2026-07-15T10:00:00Z"), "https://www.reuters.com/a", "reuters.com", 0.9);
        var ev = d.detect(item(), List.of(h)).trigger();
        assertThat(ev).isPresent();
        assertThat(ev.get().detail()).containsEntry("credibility", 0.9);
        assertThat(ev.get().detail()).containsEntry("source", "Reuters"); // existing key stays
    }

    @Test
    void macroHeadlineCarriesCredibility() {
        var d = new NewsDetector();
        var h = new NewsHeadline("Fed raises rates again", "s", "Reuters", "news",
                Instant.parse("2026-07-15T10:00:00Z"), "https://www.reuters.com/a", "reuters.com", 0.9);
        var res = d.detect(item(), List.of(h));
        assertThat(res.macroOnly()).hasSize(1);
        assertThat(res.macroOnly().get(0).credibility()).isEqualTo(0.9);
    }
}
