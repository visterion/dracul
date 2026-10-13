package de.visterion.dracul.webhook;

import de.visterion.dracul.benchmark.ScreenItemMapper;
import de.visterion.dracul.benchmark.ScreenItemMappers;
import de.visterion.dracul.strigoi.echo.StrigoiEchoWebhookController;
import de.visterion.dracul.strigoi.index.StrigoiIndexWebhookController;
import de.visterion.dracul.strigoi.insider.StrigoiInsiderWebhookController;
import de.visterion.dracul.strigoi.lazarus.StrigoiLazarusWebhookController;
import de.visterion.dracul.strigoi.merger.StrigoiMergerWebhookController;
import de.visterion.dracul.strigoi.momentum.StrigoiMomentumWebhookController;
import de.visterion.dracul.strigoi.spin.StrigoiSpinWebhookController;
import de.visterion.dracul.strigoi.tech.StrigoiTechWebhookController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Each capture hunter exposes exactly its own mapper; momentum and tech capture nothing
 *  (spec 2026-10-13 §2.3). Constructor-free: CALLS_REAL_METHODS on a Mockito instance. */
class HunterScreenMapperParityTest {

    static Stream<Arguments> hunters() {
        return Stream.of(
                Arguments.of(StrigoiEchoWebhookController.class, ScreenItemMappers.ECHO),
                Arguments.of(StrigoiLazarusWebhookController.class, ScreenItemMappers.LAZARUS),
                Arguments.of(StrigoiMergerWebhookController.class, ScreenItemMappers.MERGER),
                Arguments.of(StrigoiInsiderWebhookController.class, ScreenItemMappers.INSIDER),
                Arguments.of(StrigoiSpinWebhookController.class, ScreenItemMappers.SPIN),
                Arguments.of(StrigoiIndexWebhookController.class, ScreenItemMappers.INDEX),
                Arguments.of(StrigoiMomentumWebhookController.class, null),
                Arguments.of(StrigoiTechWebhookController.class, null));
    }

    @ParameterizedTest
    @MethodSource("hunters")
    void eachHunterExposesExactlyItsOwnMapper(Class<? extends HuntController> type, ScreenItemMapper expected) {
        HuntController hunter = Mockito.mock(type, Mockito.CALLS_REAL_METHODS);
        assertThat(hunter.screenItemMapper()).isSameAs(expected);
        if (expected != null) assertThat(expected.agent()).isEqualTo(hunter.agentName());
    }
}
