package de.visterion.dracul.executor;

import de.visterion.dracul.agent.PromptRegistry;
import de.visterion.dracul.prey.Prey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit test pinning {@link PreySignalEmitter}'s positional {@link ExecutorSignal}
 * copy — several adjacent constructor slots are all {@code String}, so a positional
 * slip would compile clean and silently corrupt signals. Also covers the
 * {@code UNKNOWN_VERSION} intake gate: a signal whose {@code agent_version} is neither
 * a registry-known prompt hash nor the emitting agent's live DB version must never reach
 * {@code signalRepo.insert(...)}.
 */
class PreySignalEmitterTest {

    private final PreySignalMapper mapper = mock(PreySignalMapper.class);
    private final ExecutorSignalRepository signalRepo = mock(ExecutorSignalRepository.class);
    private final ExecutorPositionRepository positionRepo = mock(ExecutorPositionRepository.class);
    private final ExecutorIndicators indicators = mock(ExecutorIndicators.class);
    private final PromptRegistry registry = mock(PromptRegistry.class);
    private final AgentVersionResolver versions = mock(AgentVersionResolver.class);

    private final PreySignalEmitter emitter =
            new PreySignalEmitter(mapper, signalRepo, positionRepo, indicators, registry, versions, 22, 20);

    private Prey samplePrey() {
        return new Prey(
                "prey-1", "ACME", "Acme Corp", "SPINOFF",
                0.73, "Forced index selling post-separation.",
                List.of("Dropped from parent's index"), List.of("Thin float"),
                List.of("Close below 90.00"),
                "6m", "strigoi-spin", "2026-07-08T10:00:00Z");
    }

    private ExecutorSignal mapperSignal() {
        return mapperSignal("p-abc");
    }

    private ExecutorSignal mapperSignal(String agentVersion) {
        return new ExecutorSignal(
                "sig-1", "strigoi-spin", agentVersion, "ACME", "BUY", 0.73, "SPINOFF",
                List.of("Close below 90.00"), "6m", null, "PENDING", "2026-07-08T10:00:00Z",
                null, "prey-1");
    }

    private void stubNoOpenOrPending() {
        when(positionRepo.findOpen()).thenReturn(List.of());
        when(signalRepo.findPending(Integer.MAX_VALUE)).thenReturn(List.of());
    }

    @Test
    void referencePriceFlowsIntoPersistedSignal() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal();
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(new ExecutorIndicators.Levels(true, new BigDecimal("3.1"),
                        new BigDecimal("95"), new BigDecimal("101.5")));

        emitter.emit(List.of(samplePrey()));

        ArgumentCaptor<ExecutorSignal> captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(signalRepo).insert(captor.capture());
        ExecutorSignal persisted = captor.getValue();

        assertThat(persisted.referencePrice()).isEqualByComparingTo("101.5");
        assertThat(persisted.signalId()).isEqualTo(mapped.signalId());
        assertThat(persisted.source()).isEqualTo(mapped.source());
        assertThat(persisted.agentVersion()).isEqualTo(mapped.agentVersion());
        assertThat(persisted.symbol()).isEqualTo(mapped.symbol());
        assertThat(persisted.direction()).isEqualTo(mapped.direction());
        assertThat(persisted.confidence()).isEqualTo(mapped.confidence());
        assertThat(persisted.mechanism()).isEqualTo(mapped.mechanism());
        assertThat(persisted.killCriteria()).isEqualTo(mapped.killCriteria());
        assertThat(persisted.horizon()).isEqualTo(mapped.horizon());
        assertThat(persisted.status()).isEqualTo(mapped.status());
        assertThat(persisted.createdAt()).isEqualTo(mapped.createdAt());
        assertThat(persisted.preyId()).isEqualTo(samplePrey().id());
    }

    @Test
    void unavailableLevelsLeaveReferencePriceNull() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal();
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        emitter.emit(List.of(samplePrey()));

        ArgumentCaptor<ExecutorSignal> captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(signalRepo).insert(captor.capture());
        ExecutorSignal persisted = captor.getValue();

        assertThat(persisted.referencePrice()).isNull();
        assertThat(persisted.signalId()).isEqualTo(mapped.signalId());
        assertThat(persisted.source()).isEqualTo(mapped.source());
        assertThat(persisted.agentVersion()).isEqualTo(mapped.agentVersion());
        assertThat(persisted.symbol()).isEqualTo(mapped.symbol());
        assertThat(persisted.direction()).isEqualTo(mapped.direction());
        assertThat(persisted.confidence()).isEqualTo(mapped.confidence());
        assertThat(persisted.mechanism()).isEqualTo(mapped.mechanism());
        assertThat(persisted.killCriteria()).isEqualTo(mapped.killCriteria());
        assertThat(persisted.horizon()).isEqualTo(mapped.horizon());
        assertThat(persisted.status()).isEqualTo(mapped.status());
        assertThat(persisted.createdAt()).isEqualTo(mapped.createdAt());
        assertThat(persisted.preyId()).isEqualTo(samplePrey().id());
    }

    @Test
    void knownRegistryVersionIsEmitted() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal("p-abc");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        emitter.emit(List.of(samplePrey()));

        verify(signalRepo).insert(any(ExecutorSignal.class));
    }

    @Test
    void unknownVersionIsSkippedWithoutInsert() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal("p-foreign");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(versions.versionFor("strigoi-spin")).thenReturn("p-abc");

        emitter.emit(List.of(samplePrey()));

        verify(signalRepo, never()).insert(any(ExecutorSignal.class));
    }

    @Test
    void operatorSourceIsExemptFromVersionCheck() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = new ExecutorSignal(
                "sig-1", "operator", "unknown", "ACME", "BUY", 0.73, "SPINOFF",
                List.of("Close below 90.00"), "6m", null, "PENDING", "2026-07-08T10:00:00Z");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        emitter.emit(List.of(samplePrey()));

        verify(signalRepo).insert(any(ExecutorSignal.class));
    }

    @Test
    void unknownSentinelVersionFromUnregisteredSourceIsSkipped() {
        // Source agent has no DB row -> versions.versionFor returns the "unknown" sentinel.
        // A signal carrying version "unknown" for that same source must NOT be treated as
        // known just because the two sentinel strings are equal.
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal("unknown");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(versions.versionFor("strigoi-spin")).thenReturn("unknown");

        emitter.emit(List.of(samplePrey()));

        verify(signalRepo, never()).insert(any(ExecutorSignal.class));
    }

    @Test
    void preyIdFlowsIntoPersistedSignal() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = new ExecutorSignal(
                "sig-1", "strigoi-spin", "p-abc", "ACME", "BUY", 0.73, "SPINOFF",
                List.of("Close below 90.00"), "6m", null, "PENDING", "2026-07-08T10:00:00Z",
                null, "prey-1");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        emitter.emit(List.of(samplePrey()));

        ArgumentCaptor<ExecutorSignal> captor = ArgumentCaptor.forClass(ExecutorSignal.class);
        verify(signalRepo).insert(captor.capture());
        assertThat(captor.getValue().preyId()).isEqualTo("prey-1");
    }

    @Test
    void versionMissingFromRegistryButMatchingLiveDbVersionIsEmitted() {
        stubNoOpenOrPending();
        ExecutorSignal mapped = mapperSignal("p-edited");
        when(mapper.map(any(Prey.class))).thenReturn(mapped);
        when(registry.knownHashes()).thenReturn(Set.of("p-abc"));
        when(versions.versionFor("strigoi-spin")).thenReturn("p-edited");
        when(indicators.levels(anyString(), anyInt(), anyInt()))
                .thenReturn(ExecutorIndicators.Levels.unavailable());

        emitter.emit(List.of(samplePrey()));

        verify(signalRepo).insert(any(ExecutorSignal.class));
    }
}
