package de.visterion.dracul.i18n;

/** Published when the configured language changes, so agent registrars can re-register. */
public record LanguageChangedEvent(String language) {}
