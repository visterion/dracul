package de.visterion.dracul.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Per-tool request budgets for {@link AgoraClient}, keyed by MCP tool name.
 *
 * <p>Why per-tool and not one global number: Agora's tools are not one workload. Most are a
 * single upstream GET and should fail fast; {@code get_form4_transactions} walks a market-wide
 * EDGAR window under its own 30 s aggregate deadline and cannot. Raising
 * {@code dracul.agora.timeout-ms} for everyone would license a 45 s hang on a quote lookup, so
 * the slow tool gets its own budget and every other tool keeps the global default.
 *
 * <p>Binding note: the map keys are MCP tool names and contain underscores, which Spring's
 * relaxed binding would normalise away. They must therefore be written in bracket form in YAML
 * ({@code "[get_form4_transactions]"}); {@code AgoraToolTimeoutsTest} pins that the shipped
 * configuration really binds.
 */
@ConfigurationProperties(prefix = "dracul.agora")
public record AgoraToolTimeouts(Map<String, Long> toolTimeoutMs) {

    public AgoraToolTimeouts {
        toolTimeoutMs = toolTimeoutMs == null ? Map.of() : Map.copyOf(toolTimeoutMs);
    }

    /** No overrides — every tool uses the global default. */
    public static AgoraToolTimeouts none() {
        return new AgoraToolTimeouts(Map.of());
    }

    /**
     * The request budget for {@code tool} in ms, or {@code fallbackMs} when it has no override.
     * A non-positive override is treated as absent rather than as "no timeout": a typo must not
     * be able to turn a bounded call into an unbounded one.
     */
    public long forTool(String tool, long fallbackMs) {
        Long override = toolTimeoutMs.get(tool);
        return override == null || override <= 0 ? fallbackMs : override;
    }
}
