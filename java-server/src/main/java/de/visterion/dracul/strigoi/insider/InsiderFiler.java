package de.visterion.dracul.strigoi.insider;

/** One distinct insider in a cluster. {@code role} is Agora's free-text Form-4
 *  officer title (e.g. "Chief Executive Officer"); empty when the filer is not an
 *  officer (typically a director or 10% owner). */
public record InsiderFiler(String name, String role) {}
