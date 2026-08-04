package de.visterion.dracul.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two guarantees for the per-tool Agora request budget.
 *
 * <p>1. The lookup itself: an unlisted tool keeps the global default, a listed one wins.
 *
 * <p>2. That the shipped {@code application.yaml} actually BINDS. This is not paranoia: Spring's
 * relaxed binding normalises {@code -} and {@code _} out of a property name, so an unbracketed
 * map key {@code get_form4_transactions} would silently arrive as {@code getform4transactions}
 * and the override would be dead config that nobody notices until the next production timeout.
 * The bracket form is what pins the key verbatim, and this test is what proves it.
 */
class AgoraToolTimeoutsTest {

    @Configuration
    @EnableConfigurationProperties(AgoraToolTimeouts.class)
    static class Config {}

    @Test
    void unlistedToolFallsBackToTheGlobalDefault() {
        var timeouts = new AgoraToolTimeouts(Map.of("get_form4_transactions", 45_000L));
        assertThat(timeouts.forTool("get_quote", 25_000L)).isEqualTo(25_000L);
    }

    @Test
    void listedToolWins() {
        var timeouts = new AgoraToolTimeouts(Map.of("get_form4_transactions", 45_000L));
        assertThat(timeouts.forTool("get_form4_transactions", 25_000L)).isEqualTo(45_000L);
    }

    @Test
    void nullOrNonPositiveOverrideFallsBackRatherThanDisablingTheTimeout() {
        assertThat(new AgoraToolTimeouts(null).forTool("x", 25_000L)).isEqualTo(25_000L);
        assertThat(new AgoraToolTimeouts(Map.of("x", 0L)).forTool("x", 25_000L)).isEqualTo(25_000L);
        assertThat(new AgoraToolTimeouts(Map.of("x", -1L)).forTool("x", 25_000L)).isEqualTo(25_000L);
    }

    @Test
    void shippedApplicationYamlBindsTheForm4KeyVerbatim() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .run(ctx -> {
                    AgoraToolTimeouts bound = ctx.getBean(AgoraToolTimeouts.class);
                    assertThat(bound.toolTimeoutMs())
                            .as("dracul.agora.tool-timeout-ms must bind the MCP tool name verbatim "
                                    + "— an unbracketed key is normalised to 'getform4transactions'")
                            .containsKey("get_form4_transactions");
                    assertThat(bound.forTool("get_form4_transactions", 25_000L)).isEqualTo(45_000L);
                });
    }
}
