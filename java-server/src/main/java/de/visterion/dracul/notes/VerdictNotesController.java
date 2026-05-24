package de.visterion.dracul.notes;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
public class VerdictNotesController {

    private final VerdictNotesRepository repo;
    public VerdictNotesController(VerdictNotesRepository repo) { this.repo = repo; }

    @PostMapping("/api/verdict/{id}/notes")
    public ResponseEntity<VerdictNote> addNote(@PathVariable String id,
                                                @Valid @RequestBody NoteRequest req) {
        if (!repo.verdictExists(id)) throw new NoSuchElementException("verdict " + id);
        VerdictNote n = repo.insert(id, req.body().trim());
        return ResponseEntity.created(URI.create("/api/verdict/" + id + "/notes/" + n.id()))
                .body(n);
    }

    @GetMapping("/api/verdict/{id}/notes")
    public Map<String, List<VerdictNote>> listNotes(@PathVariable String id) {
        return Map.of("notes", repo.findByVerdictId(id));
    }
}
