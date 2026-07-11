package de.visterion.dracul.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Identifies the current user from a verified Cloudflare Access JWT
 * (Cf-Access-Jwt-Assertion), stored in CurrentUserHolder. Webhook + health paths
 * are excluded (machine bearer-token auth). Bypass mode (blank CF config) honors
 * an X-Dev-User header for local/dev/test, else "default".
 */
@Component
public class CloudflareAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CloudflareAccessFilter.class);
    static final List<String> EXCLUDED =
            List.of("/api/strigoi-", "/api/voievod", "/api/voievod-outcome", "/api/gropar",
                    "/api/daywalker", "/api/executor/tools", "/api/executor/complete",
                    "/actuator/health");

    private final boolean bypass;
    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor; // null in bypass

    public CloudflareAccessFilter(
            @Value("${dracul.cloudflare.team-domain:}") String teamDomain,
            @Value("${dracul.cloudflare.aud:}") String aud,
            Environment env) throws Exception {
        boolean configured = !teamDomain.isBlank() && !aud.isBlank();
        boolean devLike = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equals("dev") || p.equals("test"));
        if (!configured && !devLike) {
            throw new IllegalStateException(
                    "Cloudflare Access config required outside dev/test: set "
                    + "dracul.cloudflare.team-domain and dracul.cloudflare.aud");
        }
        this.bypass = !configured;   // only reachable as true under a dev/test profile
        if (bypass) {
            this.jwtProcessor = null;
            log.warn("CloudflareAccessFilter in BYPASS mode (dev/test, no team-domain/aud) — X-Dev-User honored");
        } else {
            JWKSource<SecurityContext> jwks = JWKSourceBuilder
                    .create(URI.create(teamDomain + "/cdn-cgi/access/certs").toURL()).build();
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder().audience(aud).build(),
                    Set.of("email", "exp")));
            this.jwtProcessor = processor;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (EXCLUDED.stream().anyMatch(path::startsWith)) {
            chain.doFilter(req, res);
            return;
        }
        if (Boolean.TRUE.equals(req.getAttribute(LocalAccessFilter.ATTR))) {
            // Authenticated by LocalAccessFilter (ran earlier) — skip the Cloudflare JWT check.
            // CurrentUserHolder is owned (set + cleared) by LocalAccessFilter, so do not touch it.
            chain.doFilter(req, res);
            return;
        }
        try {
            if (bypass) {
                String dev = req.getHeader("X-Dev-User");
                CurrentUserHolder.set(dev == null || dev.isBlank() ? "default" : dev);
            } else {
                String token = req.getHeader("Cf-Access-Jwt-Assertion");
                if (token == null || token.isBlank()) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                JWTClaimsSet claims = jwtProcessor.process(token, null);
                String email = claims.getStringClaim("email");
                if (email == null || email.isBlank()) {
                    res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                CurrentUserHolder.set(email);
            }
            chain.doFilter(req, res);
        } catch (Exception e) {
            log.debug("Cloudflare JWT rejected for {}: {}", path, e.getMessage());
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } finally {
            CurrentUserHolder.clear();
        }
    }
}
