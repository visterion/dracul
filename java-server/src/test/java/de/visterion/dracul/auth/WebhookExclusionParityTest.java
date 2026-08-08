package de.visterion.dracul.auth;

import de.visterion.dracul.ContainerConfig;
import de.visterion.dracul.vistierie.VistierieClient;
import de.visterion.dracul.webhook.BearerTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity gate between {@link CloudflareAccessFilter#EXCLUDED} and the controllers that actually
 * authenticate with a {@link BearerTokenVerifier} (machine/webhook auth, not the human Cloudflare
 * Access JWT). This is the regression rail for the {@code /api/renfield/complete} 401: renfield's
 * completion webhook carries a {@code BearerTokenVerifier} field exactly like every other hunter
 * webhook, but was left out of {@code EXCLUDED} for ten days before anyone noticed the silent
 * rejection.
 *
 * <p>Direction 1: every machine-auth endpoint must be covered by an {@code EXCLUDED} prefix — this
 * is the rule that would have caught the renfield bug. Direction 2: no NON-machine-auth endpoint
 * may be covered — this is what stops a future prefix (like the once-considered {@code /api/renfield})
 * from accidentally letting an operator-facing endpoint (Task 4's
 * {@code GET /api/renfield/proposals}) bypass human auth.
 *
 * <p>Following {@code ToolEndpointResponseRuleIT#derivedToolEndpointListMatchesTheSevenExpectedPaths}
 * (see its class javadoc, "Two things make this test worth having"): the derived machine-auth set is
 * pinned against a hand-written expectation, because every one of these controllers is
 * {@code @ConditionalOnProperty} default {@code false} — a forgotten property in this class's
 * {@code @TestPropertySource} would silently shrink the derived set, in the worst case to zero, and
 * both directional assertions below would then pass vacuously over an empty list.
 */
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "dracul.daywalker.enabled=true", "dracul.voievod.enabled=true",
        "dracul.strigoi.echo.enabled=true", "dracul.strigoi.lazarus.enabled=true",
        "dracul.strigoi.merger.enabled=true", "dracul.strigoi.index.enabled=true",
        "dracul.strigoi.insider.enabled=true", "dracul.strigoi.spin.enabled=true",
        "dracul.renfield.enabled=true",
        "dracul.gropar.enabled=true",
        "dracul.voievod-outcome.enabled=true",
        "dracul.voievod-outcome.webhook-token=test-token",
        "dracul.voievod-outcome.schedule=0 0 7 * * 6",
        // Without these two, ExecutorWebhookController and DaywalkerDeepController never register
        // — the two controllers this rule exists to exercise (renfield's sibling webhooks) would
        // silently drop out of the derived set and the test would prove nothing about them.
        "dracul.executor.enabled=true",
        "dracul.daywalker-deep.enabled=true"
})
class WebhookExclusionParityTest {

    // Hand-written pin (see class javadoc): every full path of a controller that authenticates via
    // BearerTokenVerifier, as of this commit. A genuinely new machine-auth endpoint must be added
    // here deliberately — that is the point of pinning rather than only asserting coverage.
    private static final List<String> EXPECTED_MACHINE_AUTH_PATHS = List.of(
            "/api/strigoi-index/tools/fetch-candidates", "/api/strigoi-index/complete",
            "/api/strigoi-spin/tools/fetch-candidates", "/api/strigoi-spin/complete",
            "/api/strigoi-insider/tools/fetch-clusters", "/api/strigoi-insider/complete",
            "/api/strigoi-lazarus/tools/fetch-candidates", "/api/strigoi-lazarus/complete",
            "/api/strigoi-merger/tools/fetch-candidates", "/api/strigoi-merger/complete",
            "/api/strigoi-echo/tools/fetch-candidates", "/api/strigoi-echo/tools/fetch-news",
            "/api/strigoi-echo/complete",
            "/api/gropar/tools/fetch-held-positions", "/api/gropar/complete",
            "/api/daywalker/events", "/api/daywalker/complete",
            "/api/daywalker-deep/complete",
            "/api/voievod-outcome/tools/fetch-elapsed-prey", "/api/voievod-outcome/complete",
            "/api/executor/tools/fetch-pending-signals", "/api/executor/tools/get-account",
            "/api/executor/tools/list-positions", "/api/executor/tools/place-entry",
            "/api/executor/tools/submit-decision", "/api/executor/tools/fetch-open-positions",
            "/api/executor/tools/exit-position", "/api/executor/tools/add-tranche",
            "/api/executor/complete",
            "/api/voievod/tools/fetch-candidates", "/api/voievod/complete",
            "/api/renfield/complete");

    // Keeps the context bootable without a real Vistierie; GenericAgentRegistrar calls
    // getAgent/registerAgent on this mock at startup (mirrors AgentRegistrationParityTest).
    @MockitoBean VistierieClient vistierie;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyMachineAuthEndpointIsExcludedFromTheCloudflareFilter() {
        var machineAuthPaths = machineAuthPaths();

        assertThat(machineAuthPaths)
                .as("derived machine-auth endpoint set (pinned so a forgotten enable-property "
                        + "cannot silently shrink it to nothing)")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MACHINE_AUTH_PATHS);

        for (String path : machineAuthPaths) {
            assertThat(CloudflareAccessFilter.EXCLUDED.stream().anyMatch(path::startsWith))
                    .as("machine-auth endpoint %s is not covered by any CloudflareAccessFilter.EXCLUDED "
                            + "prefix — its completion webhook / tool call will be rejected 401 by the "
                            + "human Cloudflare Access filter", path)
                    .isTrue();
        }
    }

    @Test
    void noNonMachineAuthEndpointIsExcludedFromTheCloudflareFilter() {
        var machineAuthPaths = machineAuthPaths();

        for (String path : allPaths()) {
            if (machineAuthPaths.contains(path)) continue;
            assertThat(CloudflareAccessFilter.EXCLUDED.stream().anyMatch(path::startsWith))
                    .as("non-machine-auth endpoint %s is covered by a CloudflareAccessFilter.EXCLUDED "
                            + "prefix — it would bypass human Cloudflare Access auth entirely", path)
                    .isFalse();
        }
    }

    /** Full request paths of every handler registered on the application's own
     *  {@link RequestMappingHandlerMapping} — deliberately NOT the actuator mappings: {@code
     *  /actuator/health} sits in {@code EXCLUDED} without a {@link BearerTokenVerifier} handler
     *  behind it at all, and enumerating it here would misfire direction 2. */
    private List<String> allPaths() {
        return handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .toList();
    }

    /** Machine-auth = the handler's bean type, or any of its superclasses, declares a field of
     *  type {@link BearerTokenVerifier}. Walking the hierarchy (not just the bean's own class) is
     *  required: the six strigoi hunters carry their verifier on {@code HuntController}, the shared
     *  base class, not on the subclass itself. */
    private List<String> machineAuthPaths() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> isMachineAuth(e.getValue()))
                .flatMap(e -> e.getKey().getPathPatternsCondition().getPatternValues().stream())
                .toList();
    }

    private static boolean isMachineAuth(HandlerMethod method) {
        for (Class<?> c = method.getBeanType(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var field : c.getDeclaredFields()) {
                if (field.getType() == BearerTokenVerifier.class) return true;
            }
        }
        return false;
    }
}
