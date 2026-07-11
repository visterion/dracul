package de.visterion.dracul.depot;

/** A configured Agora broker connection, as returned by {@code list_connections}. */
public record DepotConnection(String id, String provider, String environment,
                               String status, String probedAt) {
}
