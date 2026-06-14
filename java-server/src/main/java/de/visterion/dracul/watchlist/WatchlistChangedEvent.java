package de.visterion.dracul.watchlist;

/**
 * Published after any watchlist mutation (add / tag change / position change /
 * delete). A marker event — listeners recompute whatever state they need rather
 * than inferring a delta. Single-tenant, so it carries no payload.
 */
public record WatchlistChangedEvent() {
}
