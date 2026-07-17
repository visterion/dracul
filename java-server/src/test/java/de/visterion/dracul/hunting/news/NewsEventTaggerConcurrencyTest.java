package de.visterion.dracul.hunting.news;

import de.visterion.dracul.hunting.agora.NewsHeadline;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins that one shared NewsEventTagger instance is safe under the Daywalker
 *  virtual-thread fan-out: stateless, deterministic under concurrent use. */
class NewsEventTaggerConcurrencyTest {

    @Test
    void sharedInstanceIsDeterministicUnderConcurrentUse() throws Exception {
        var tagger = new NewsEventTagger();
        var headline = new NewsHeadline("Company cuts guidance after earnings miss",
                "Shares fall", "wire", "news", Instant.parse("2026-06-03T12:00:00Z"), null);
        var expected = tagger.tag(headline);
        assertThat(expected).isNotEmpty();

        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<java.util.Set<NewsEventType>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                futures.add(exec.submit(() -> tagger.tag(headline)));
            }
            for (var f : futures) {
                assertThat(f.get()).isEqualTo(expected);
            }
        }
    }
}
