package de.visterion.dracul.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for a silent ordering defect found alongside the prompt-propagation bug.
 *
 * <p>{@code AgentDefinitionBootstrap}, {@code PromptRegistryValidator} and
 * {@code GenericAgentRegistrar} each carried a class-level {@code @Order} with a comment claiming
 * a startup sequence. Spring ignores it: {@code ApplicationListenerMethodAdapter.resolveOrder}
 * reads {@code @Order} from the listener METHOD only and falls back to {@code LOWEST_PRECEDENCE}
 * otherwise, so all three ran in undefined order. Production proved it — the registrar
 * ({@code @Order(20)}) logged at 21:57:02.956, before the validator ({@code @Order(10)}) at
 * 21:57:02.978.
 *
 * <p>That was harmless while the bootstrap only inserted. It is not harmless now that the bootstrap
 * REWRITES prompts the registrar then publishes, so the ordering is pinned here.
 */
class StartupListenerOrderTest {

    private static int readyListenerOrder(Class<?> type) throws Exception {
        Method found = null;
        for (Method m : type.getDeclaredMethods()) {
            EventListener ann = m.getAnnotation(EventListener.class);
            if (ann == null) {
                continue;
            }
            boolean ready = m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == ApplicationReadyEvent.class;
            for (Class<?> c : ann.value()) {
                ready |= c == ApplicationReadyEvent.class;
            }
            if (ready) {
                found = m;
            }
        }
        assertThat(found)
                .as("%s must have an @EventListener(ApplicationReadyEvent.class) method",
                        type.getSimpleName())
                .isNotNull();
        Order order = found.getAnnotation(Order.class);
        assertThat(order)
                .as("@Order must sit on %s.%s — a class-level @Order is silently ignored for "
                        + "@EventListener methods", type.getSimpleName(), found.getName())
                .isNotNull();
        return order.value();
    }

    @Test
    void bootstrapReconcilesBeforeTheValidatorChecksAndBeforeTheRegistrarPublishes() throws Exception {
        int bootstrap = readyListenerOrder(AgentDefinitionBootstrap.class);
        int validator = readyListenerOrder(PromptRegistryValidator.class);
        int registrar = readyListenerOrder(GenericAgentRegistrar.class);

        assertThat(bootstrap)
                .as("bootstrap writes the store both others read")
                .isLessThan(validator);
        assertThat(validator)
                .as("validator must observe the reconciled store, not the pre-reconcile one")
                .isLessThan(registrar);
    }
}
