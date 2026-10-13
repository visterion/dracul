package de.visterion.dracul.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores pre-screen candidates in {@code screen_candidate} (spec 2026-10-13 §2.3/§2.4).
 * {@link #record} is the live, best-effort path called from {@code HuntController.handleFetch}: it
 * never throws, so a capture failure can never change a hunt response. {@link #store} is the shared
 * core, also used by the backfill import, and lets failures propagate.
 */
@Component
public class ScreenCandidateRecorder {

    /** Stable log prefix of every capture failure — the daily analysis and the decision doc grep it. */
    public static final String WARN_PREFIX = "screen-candidate capture failed";
    public static final String SOURCE_LIVE = "LIVE";
    public static final String SOURCE_BACKFILL = "BACKFILL";

    private static final Logger log = LoggerFactory.getLogger(ScreenCandidateRecorder.class);

    private final ScreenCandidateRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public ScreenCandidateRecorder(ScreenCandidateRepository repo, ObjectMapper mapper) {
        this(repo, mapper, Clock.systemUTC());
    }

    ScreenCandidateRecorder(ScreenCandidateRepository repo, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void record(String agent, ScreenItemMapper itemMapper, List<?> items) {
        try {
            JsonNode tree = mapper.valueToTree(items);
            List<JsonNode> nodes = new ArrayList<>();
            tree.forEach(nodes::add);
            CaptureCounts c = store(agent, Instant.now(clock), itemMapper, nodes, SOURCE_LIVE);
            log.info("screen-candidate capture {}: stored={} duplicate={} symbol-less={}",
                    agent, c.stored(), c.duplicate(), c.symbolLess());
        } catch (RuntimeException e) {
            log.warn(WARN_PREFIX + " for {}: {}", agent, e.toString());
        }
    }

    public CaptureCounts store(String agent, Instant fetchedAt, ScreenItemMapper itemMapper,
                               List<JsonNode> items, String source) {
        int stored = 0;
        int duplicate = 0;
        int symbolLess = 0;
        for (JsonNode item : items) {
            Optional<ScreenItem> mapped = itemMapper.map(item);
            if (mapped.isEmpty()) {
                symbolLess++;
                continue;
            }
            if (repo.insert(agent, fetchedAt, mapped.get(), source)) stored++;
            else duplicate++;
        }
        return new CaptureCounts(stored, duplicate, symbolLess);
    }
}
