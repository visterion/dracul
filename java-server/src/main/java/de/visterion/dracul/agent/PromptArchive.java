package de.visterion.dracul.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The set of prompt bodies Dracul has ever <em>shipped</em> for an agent, keyed by agent name:
 * every {@code prompts/archive/<agent>/<version>.md} plus the currently bundled
 * {@code prompts/<agent>.md}.
 *
 * <p>This is the oracle {@link AgentDefinitionBootstrap} uses to tell a <em>stale default</em>
 * (a stored prompt that is byte-identical to some default we once shipped, so the operator never
 * touched it) apart from a <em>genuine operator edit</em> (a stored prompt nobody ever shipped,
 * typically typed into the agent-definition UI). The first may be reconciled to the current
 * bundled default; the second must be preserved.
 *
 * <p><b>The archive is therefore load-bearing at runtime</b> — it is no longer a source-tree-only
 * convention. Maven copies {@code src/main/resources} wholesale, so the files land in the fat jar
 * under {@code BOOT-INF/classes/prompts/archive/}; {@code PromptArchiveTest} pins that they are
 * actually reachable through the classpath.
 *
 * <p>A missing archive entry is not fatal: it degrades an otherwise-reconcilable stale default into
 * a loud "looks like an operator edit" warning, which is a safe (never data-destroying) failure
 * direction.
 *
 * <p><b>Known, accepted ambiguity:</b> an operator who deliberately rolls an agent back to an older
 * <em>shipped</em> version through the UI produces a stored prompt indistinguishable from a stale
 * one, and the next boot reconciles it forward. That is unavoidable — the two states are
 * byte-identical — and it is the benign direction: the rollback is undone loudly (an INFO naming
 * both hashes), never silently, and no text the operator authored is lost. A durable rollback means
 * changing the bundled prompt in the repo.
 */
@Component
public class PromptArchive {

    private static final Logger log = LoggerFactory.getLogger(PromptArchive.class);
    private static final String ARCHIVE_PATTERN = "classpath*:prompts/archive/*/*.md";

    private final Map<String, Set<String>> shippedHashes;

    public PromptArchive() {
        this.shippedHashes = load();
        if (shippedHashes.isEmpty()) {
            log.warn("prompt archive: no archived prompt versions found on the classpath ({}) — "
                    + "every stale stored prompt will be misread as an operator edit and will block "
                    + "propagation of its bundled default", ARCHIVE_PATTERN);
        } else {
            log.info("prompt archive: {} agent(s), {} archived prompt version(s) loaded",
                    shippedHashes.size(),
                    shippedHashes.values().stream().mapToInt(Set::size).sum());
        }
    }

    private static Map<String, Set<String>> load() {
        var resolver = new PathMatchingResourcePatternResolver();
        Map<String, Set<String>> byAgent = new HashMap<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(ARCHIVE_PATTERN);
        } catch (Exception e) {
            log.warn("prompt archive: classpath scan failed ({}) — treating the archive as empty",
                    e.toString());
            return Map.of();
        }
        for (Resource r : resources) {
            try {
                String agent = agentOf(r.getURL().getPath());
                if (agent == null) {
                    continue;
                }
                String raw = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                byAgent.computeIfAbsent(agent, k -> new HashSet<>())
                        .add(PromptHashes.hash(PromptDocument.parse(raw).body()));
            } catch (Exception e) {
                log.warn("prompt archive: skipping unreadable entry {}: {}", r, e.toString());
            }
        }
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        byAgent.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Map.copyOf(copy);
    }

    /**
     * Extracts {@code <agent>} from a path ending in {@code .../<agent>/<version>.md}. Works for a
     * plain file path and for a nested-jar URL path alike, since both end in the same two segments.
     */
    static String agentOf(String path) {
        int file = path.lastIndexOf('/');
        if (file <= 0) {
            return null;
        }
        int dir = path.lastIndexOf('/', file - 1);
        if (dir < 0) {
            return null;
        }
        String agent = path.substring(dir + 1, file);
        return agent.isEmpty() ? null : agent;
    }

    /** Archived (previously shipped) body hashes for {@code agent}; never null. */
    public Set<String> archivedHashes(String agent) {
        return shippedHashes.getOrDefault(agent, Set.of());
    }

    /**
     * True if {@code storedPrompt} is byte-identical to a prompt body Dracul shipped for
     * {@code agent} — either the current bundled default or any archived version. That is exactly
     * the condition under which overwriting the stored prompt cannot destroy operator intent.
     */
    public boolean wasShipped(String agent, String storedPrompt, String bundledPrompt) {
        if (storedPrompt == null) {
            return false;
        }
        if (storedPrompt.equals(bundledPrompt)) {
            return true;
        }
        return archivedHashes(agent).contains(PromptHashes.hash(storedPrompt));
    }
}
