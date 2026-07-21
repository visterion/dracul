package de.visterion.dracul.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the deployment-private "how Dracul decides" markdown doc from a
 * read-only mounted path. The file is never in git/image; it is mounted at
 * runtime and pointed at via {@code dracul.decision-doc.path}.
 *
 * <p>This bean is intentionally an unconditional {@code @RestController} (no
 * {@code @ConditionalOnProperty}). {@code SpaFallbackController} maps dot-free
 * two-segment paths like {@code /api/decision-doc}; were this bean absent the
 * request would fall through to the SPA and yield a 200 text/html instead of a
 * 404. The "off" state is therefore a content-level 404 when the path is blank.
 */
@RestController
public class DecisionDocController {

    private static final Logger log = LoggerFactory.getLogger(DecisionDocController.class);

    private final String path;
    private final long maxBytes;

    public DecisionDocController(
            @Value("${dracul.decision-doc.path:}") String path,
            @Value("${dracul.decision-doc.max-bytes:1048576}") long maxBytes) {
        this.path = path;
        this.maxBytes = maxBytes;
    }

    public record DecisionDoc(String markdown) {}

    @GetMapping("/api/decision-doc")
    public ResponseEntity<DecisionDoc> get() {
        if (path == null || path.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Path p = Path.of(path);
        try {
            if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
                log.warn("decision-doc path {} is not a readable regular file", p);
                return ResponseEntity.notFound().build();
            }
            long size = Files.size(p);
            if (size > maxBytes) {
                log.warn("decision-doc {} exceeds max-bytes {}>{}", p, size, maxBytes);
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(new DecisionDoc(Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            log.warn("decision-doc read failed for {}: {}", p, e.toString());
            return ResponseEntity.notFound().build();
        }
    }
}
