package de.visterion.dracul.vistierie;

public record VistierieRunEvent(
        long id,
        String ts,
        String level,
        String type,
        Object payload
) {}
