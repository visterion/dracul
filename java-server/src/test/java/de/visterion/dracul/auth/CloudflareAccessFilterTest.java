package de.visterion.dracul.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareAccessFilterTest {

    static final String AUD = "testaud";
    HttpServer server;
    String teamDomain;
    RSAKey rsaJwk;

    @BeforeEach
    void setUp() throws Exception {
        rsaJwk = new RSAKeyGenerator(2048).keyID("k1").generate();
        String jwks = new JWKSet(rsaJwk.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cdn-cgi/access/certs", ex -> {
            byte[] b = jwks.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        teamDomain = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    private static MockEnvironment devEnv() {
        var e = new MockEnvironment();
        e.setActiveProfiles("dev");
        return e;
    }

    private String mint(String email, String aud, Instant exp) throws Exception {
        var claims = new JWTClaimsSet.Builder().audience(aud).claim("email", email)
                .expirationTime(Date.from(exp)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(rsaJwk));
        return jwt.serialize();
    }

    /** Runs the filter and returns [resolvedUser-or-null, responseStatus]. */
    private Object[] run(CloudflareAccessFilter filter, MockHttpServletRequest req) throws Exception {
        var res = new MockHttpServletResponse();
        var captured = new AtomicReference<String>();
        FilterChain chain = (rq, rs) -> captured.set(CurrentUserHolder.get());
        filter.doFilter(req, res, chain);
        return new Object[]{ captured.get(), res.getStatus() };
    }

    @Test void validJwtSetsUser() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader("Cf-Access-Jwt-Assertion", mint("alice@x.com", AUD, Instant.now().plusSeconds(300)));
        var r = run(filter, req);
        assertThat(r[0]).isEqualTo("alice@x.com");
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void missingTokenIs401() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("GET", "/api/watchlist"));
        assertThat(r[1]).isEqualTo(401);
    }

    @Test void wrongAudienceIs401() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader("Cf-Access-Jwt-Assertion", mint("alice@x.com", "other", Instant.now().plusSeconds(300)));
        assertThat(run(filter, req)[1]).isEqualTo(401);
    }

    @Test void expiredIs401() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader("Cf-Access-Jwt-Assertion", mint("alice@x.com", AUD, Instant.now().minusSeconds(60)));
        assertThat(run(filter, req)[1]).isEqualTo(401);
    }

    @Test void webhookPathIsExcluded() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("POST", "/api/strigoi-spin/complete"));
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void executorToolWebhookIsExcluded() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("POST", "/api/executor/tools/place-entry"));
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void voievodOutcomeWebhookIsExcluded() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var fetch = run(filter, new MockHttpServletRequest("POST", "/api/voievod-outcome/tools/fetch-elapsed-prey"));
        assertThat(fetch[1]).isEqualTo(200);
        var complete = run(filter, new MockHttpServletRequest("POST", "/api/voievod-outcome/complete"));
        assertThat(complete[1]).isEqualTo(200);
    }

    @Test void daywalkerDeepWebhookIsExcluded() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("POST", "/api/daywalker-deep/complete"));
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void executorCompleteWebhookIsExcluded() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("POST", "/api/executor/complete"));
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void executorSignalsOperatorPathStillEnforced() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("GET", "/api/executor/signals"));
        assertThat(r[1]).isEqualTo(401);
    }

    @Test void executorRunOperatorPathStillEnforced() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv());
        var r = run(filter, new MockHttpServletRequest("POST", "/api/executor/run"));
        assertThat(r[1]).isEqualTo(401);
    }

    @Test void bypassModeUsesDevUserHeaderElseDefault() throws Exception {
        var filter = new CloudflareAccessFilter("", "", devEnv());
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader("X-Dev-User", "bob@x.com");
        assertThat(run(filter, req)[0]).isEqualTo("bob@x.com");

        var req2 = new MockHttpServletRequest("GET", "/api/watchlist");
        assertThat(run(filter, req2)[0]).isEqualTo("default");
    }

    @Test void blankConfigOutsideDevProfileFailsStartup() {
        var prodEnv = new MockEnvironment(); // no active profiles
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new CloudflareAccessFilter("", "", prodEnv));
    }

    @Test void localAccessAttributeSkipsJwt() throws Exception {
        var filter = new CloudflareAccessFilter(teamDomain, AUD, devEnv()); // configured (not bypass)
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.setAttribute(LocalAccessFilter.ATTR, Boolean.TRUE);             // no JWT header present
        var r = run(filter, req);
        assertThat(r[1]).isEqualTo(200);   // would be 401 without the attribute (cf. missingTokenIs401)
        assertThat(r[0]).isEqualTo("default"); // CF filter did not set a user
    }
}
