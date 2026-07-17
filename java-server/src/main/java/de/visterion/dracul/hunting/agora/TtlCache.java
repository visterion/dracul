package de.visterion.dracul.hunting.agora;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal expiring cache with per-entry TTL (T2.2, pattern borrowed from Agora's TtlCache;
 * Dracul had none). An entry may hold a NULL value — negative caching — which is why
 * {@link #contains} exists separately from {@link #get}. Expired entries are replaced on
 * the next put; no background eviction (the symbol universe is small).
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {}

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();

    /** Present AND unexpired (an entry whose value is null still counts as present). */
    public boolean contains(K key) {
        Entry<V> e = map.get(key);
        if (e == null) return false;
        if (!Instant.now().isBefore(e.expiresAt())) {
            map.remove(key, e);
            return false;
        }
        return true;
    }

    /** The cached value, or null when absent, expired, or negatively cached. */
    public V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null || !Instant.now().isBefore(e.expiresAt())) return null;
        return e.value();
    }

    public void put(K key, V value, Duration ttl) {
        map.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }
}
