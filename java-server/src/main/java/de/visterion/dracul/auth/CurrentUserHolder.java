package de.visterion.dracul.auth;

/** Request-thread holder for the authenticated user's email. Set by CloudflareAccessFilter,
 *  cleared at the end of each request. "default" when running in bypass mode with no X-Dev-User. */
public final class CurrentUserHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CurrentUserHolder() {}

    public static void set(String email) { CURRENT.set(email); }
    public static String get() { String u = CURRENT.get(); return u == null ? "default" : u; }
    public static void clear() { CURRENT.remove(); }
}
