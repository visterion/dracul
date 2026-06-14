package de.visterion.dracul.agent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/settings/agents")
public class AgentDefinitionController {

    private final AgentDefinitionStore store;
    private final AgentDefinitionValidator validator;
    private final List<AgentDefaultProvider> providers;
    private final ApplicationEventPublisher events;

    public AgentDefinitionController(AgentDefinitionStore store,
                                     AgentDefinitionValidator validator,
                                     List<AgentDefaultProvider> providers,
                                     ApplicationEventPublisher events) {
        this.store = store;
        this.validator = validator;
        this.providers = providers;
        this.events = events;
    }

    public record ToolEdit(String toolName, String description) {}
    public record EditRequest(String prompt, String schedule, String modelPurpose,
                              boolean enabled, int maxTurns, int maxRunSeconds,
                              List<ToolEdit> tools) {}

    @GetMapping("/{name}/definition")
    public ResponseEntity<AgentDefinition> get(@PathVariable String name) {
        return store.find(name).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{name}/definition")
    public ResponseEntity<?> put(@PathVariable String name, @RequestBody EditRequest body) {
        var current = store.find(name).orElse(null);
        if (current == null) return ResponseEntity.notFound().build();

        var toolList = body.tools() == null ? List.<ToolEdit>of() : body.tools();
        var tools = IntStream.range(0, toolList.size())
                .mapToObj(i -> new ToolBinding(toolList.get(i).toolName(),
                        toolList.get(i).description(), null, i))
                .toList();
        // Editable fields only; structural fields (schema, completion path,
        // event-source) carry over from current.
        var edited = new AgentDefinition(name, body.modelPurpose(), body.prompt(),
                current.outputSchema(), body.schedule(), body.maxTurns(), body.maxRunSeconds(),
                current.completionPath(), current.eventSourcePath(),
                current.sessionDurationSeconds(), current.pollIntervalSeconds(),
                body.enabled(), tools);

        var error = validator.validate(edited);
        if (error.isPresent()) return ResponseEntity.badRequest().body(Map.of("error", error.get()));

        saveAndPublish(edited);
        return ResponseEntity.ok(edited);
    }

    @PostMapping("/{name}/definition/reset")
    public ResponseEntity<?> reset(@PathVariable String name) {
        var def = providers.stream().map(AgentDefaultProvider::defaultDefinition)
                .filter(d -> d.name().equals(name)).findFirst().orElse(null);
        if (def == null) return ResponseEntity.notFound().build();
        saveAndPublish(def);
        return ResponseEntity.ok(def);
    }

    private void saveAndPublish(AgentDefinition d) {
        store.save(d);
        events.publishEvent(new AgentDefinitionChangedEvent(d.name()));
    }
}
