package de.visterion.dracul.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Opt-in local access that bypasses Cloudflare Access for requests bearing a configured shared
 * secret, authenticating them as the primary operator. Default off: active only when
 * {@code dracul.local-access.enabled} is true AND {@code dracul.local-access.token} is non-blank.
 * Runs before {@link CloudflareAccessFilter}; on a match it sets the {@link #ATTR} request
 * attribute that tells the CF filter to skip its JWT check. Security note in operations.md.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalAccessFilter extends OncePerRequestFilter {

    static final String ATTR = "localAccess";
    static final String HEADER = "X-Local-Access-Token";
    static final String COOKIE = "DRACUL_LAT";
    static final String QUERY_PARAM = "lat";

    private static final Logger log = LoggerFactory.getLogger(LocalAccessFilter.class);

    private final boolean active;
    private final String token;
    private final String primaryUser;

    public LocalAccessFilter(
            @Value("${dracul.local-access.enabled:false}") boolean enabled,
            @Value("${dracul.local-access.token:}") String token,
            @Value("${dracul.primary-user-email:}") String primaryUser) {
        boolean tokenSet = token != null && !token.isBlank();
        this.active = enabled && tokenSet;
        this.token = token;
        this.primaryUser = (primaryUser == null || primaryUser.isBlank()) ? "default" : primaryUser;
        if (enabled && !tokenSet) {
            log.warn("dracul.local-access.enabled=true but token is blank — local access DISABLED "
                    + "(fail-safe). Set DRACUL_LOCAL_ACCESS_TOKEN to enable.");
        }
        if (this.active) {
            log.warn("Local access ENABLED — requests with a valid {} header / {} cookie bypass "
                    + "Cloudflare Access as {}.", HEADER, COOKIE, this.primaryUser);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!active || CloudflareAccessFilter.EXCLUDED.stream().anyMatch(path::startsWith)) {
            chain.doFilter(req, res);
            return;
        }

        boolean fromQuery = false;
        String presented = req.getHeader(HEADER);
        if (presented == null) presented = cookieValue(req);
        if (presented == null) {
            presented = req.getParameter(QUERY_PARAM);
            fromQuery = presented != null;
        }

        if (presented == null || !constantTimeEquals(presented, token)) {
            chain.doFilter(req, res);   // no/invalid token → let CloudflareAccessFilter decide
            return;
        }

        if (fromQuery) {
            // Exchange the URL token for an HttpOnly cookie, then redirect to a clean URL so the
            // token does not linger in the address bar / browser history.
            Cookie c = new Cookie(COOKIE, token);
            c.setHttpOnly(true);
            c.setPath("/");
            c.setAttribute("SameSite", "Lax");
            // Secure mirrors the request: hardened over https, but NOT forced — the local-access
            // route is intentionally reachable over plain http on the LAN (see operations.md), where
            // a forced Secure flag would suppress the cookie and break the browser flow.
            c.setSecure(req.isSecure());
            res.addCookie(c);
            res.sendRedirect(pathWithoutLat(req));
            return;
        }

        CurrentUserHolder.set(primaryUser);
        req.setAttribute(ATTR, Boolean.TRUE);
        try {
            chain.doFilter(req, res);
        } finally {
            CurrentUserHolder.clear();
        }
    }

    private static String cookieValue(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Rebuilds the request path with the {@code lat} query param removed (keeps other params). */
    private static String pathWithoutLat(HttpServletRequest req) {
        String q = req.getQueryString();
        String base = req.getRequestURI();
        if (q == null) return base;
        String filtered = Arrays.stream(q.split("&"))
                .filter(p -> !p.equals(QUERY_PARAM) && !p.startsWith(QUERY_PARAM + "="))
                .reduce((x, y) -> x + "&" + y).orElse("");
        return filtered.isEmpty() ? base : base + "?" + filtered;
    }
}
