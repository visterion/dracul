package de.visterion.dracul.agent;

import de.visterion.dracul.settings.AppSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptRegistryValidatorTest {

    private static final String HEALTH_KEY = "health.prompt_registry";

    private AgentDefinition defWithPrompt(String name, String promptText) {
        return new AgentDefinition(
                name, "reasoning", promptText, null,
                null, 5, 300, "/completion", null, null, null, true, List.of());
    }

    @Test
    void matchingFileAndDbSetsOkFlag() {
        String body = PromptDocument.bodyFromClasspath("prompts/strigoi-spin.md");
        String hash = PromptHashes.hash(body);

        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(defWithPrompt("strigoi-spin", body)));

        PromptRegistry registry = mock(PromptRegistry.class);
        when(registry.entry("strigoi-spin"))
                .thenReturn(Optional.of(new PromptRegistry.Entry("1.0.0", hash)));

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings).put(HEALTH_KEY, "OK");
    }

    @Test
    void fileHashMismatchSetsMismatchFlag() {
        String body = PromptDocument.bodyFromClasspath("prompts/strigoi-spin.md");

        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(defWithPrompt("strigoi-spin", body)));

        PromptRegistry registry = mock(PromptRegistry.class);
        // Registry hash deliberately wrong / stale relative to the bundled file.
        when(registry.entry("strigoi-spin"))
                .thenReturn(Optional.of(new PromptRegistry.Entry("1.0.0", "p-000000000000")));

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings).put(eq(HEALTH_KEY), startsWith("MISMATCH:"));
        verify(settings).put(eq(HEALTH_KEY), eq("MISMATCH:strigoi-spin"));
    }

    @Test
    void versionMismatchSetsMismatchFlag() {
        String body = PromptDocument.bodyFromClasspath("prompts/strigoi-spin.md");
        String hash = PromptHashes.hash(body);

        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(defWithPrompt("strigoi-spin", body)));

        PromptRegistry registry = mock(PromptRegistry.class);
        // Hash matches but the header version the registry expects does not.
        when(registry.entry("strigoi-spin"))
                .thenReturn(Optional.of(new PromptRegistry.Entry("9.9.9", hash)));

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings).put(eq(HEALTH_KEY), eq("MISMATCH:strigoi-spin"));
    }

    @Test
    void dbDivergenceAloneDoesNotSetMismatchFlag() {
        String fileBody = PromptDocument.bodyFromClasspath("prompts/strigoi-spin.md");
        String fileHash = PromptHashes.hash(fileBody);

        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        // DB prompt text differs from the bundled default (a legitimate user edit).
        when(store.findAllEnabled())
                .thenReturn(List.of(defWithPrompt("strigoi-spin", "user edited prompt text")));

        PromptRegistry registry = mock(PromptRegistry.class);
        when(registry.entry("strigoi-spin"))
                .thenReturn(Optional.of(new PromptRegistry.Entry("1.0.0", fileHash)));

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings).put(HEALTH_KEY, "OK");
        verify(settings, never()).put(eq(HEALTH_KEY), startsWith("MISMATCH:"));
    }

    @Test
    void missingRegistryEntrySetsMismatchFlag() {
        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(defWithPrompt("ghost-agent", "body")));

        PromptRegistry registry = mock(PromptRegistry.class);
        when(registry.entry("ghost-agent")).thenReturn(Optional.empty());

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings).put(eq(HEALTH_KEY), eq("MISMATCH:ghost-agent"));
    }

    @Test
    void multipleMismatchesAreJoinedInFlag() {
        AgentDefinitionStore store = mock(AgentDefinitionStore.class);
        when(store.findAllEnabled()).thenReturn(List.of(
                defWithPrompt("agent-a", "a"), defWithPrompt("agent-b", "b")));

        PromptRegistry registry = mock(PromptRegistry.class);
        when(registry.entry("agent-a")).thenReturn(Optional.empty());
        when(registry.entry("agent-b")).thenReturn(Optional.empty());

        AppSettingsRepository settings = mock(AppSettingsRepository.class);

        new PromptRegistryValidator(store, registry, settings).validate();

        verify(settings, times(1)).put(eq(HEALTH_KEY), eq("MISMATCH:agent-a,agent-b"));
    }
}
