package de.visterion.dracul.hivemem;

/**
 * One search hit surfaced to the daywalker/renfield pre-fetch seam. {@code content} is the raw
 * (JSON-serialized) cell body text as stored by {@code add_cell} — callers that need structured
 * fields out of it parse it themselves; this record does not assume a shape.
 */
public record MemoryHit(String cellId, String summary, String content) {}
