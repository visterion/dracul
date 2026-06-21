package de.visterion.dracul.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAccessFilterTest {

    private static final String TOKEN = "s3cret-token";
    private static final String USER = "alice@x.com";

    /** Runs the filter; returns [capturedUserInChain, status, response, attrInChain]. */
    private Object[] run(LocalAccessFilter filter, MockHttpServletRequest req) throws Exception {
        var res = new MockHttpServletResponse();
        var user = new AtomicReference<String>();
        var attr = new AtomicReference<Object>();
        FilterChain chain = (rq, rs) -> {
            user.set(CurrentUserHolder.get());
            attr.set(rq.getAttribute(LocalAccessFilter.ATTR));
        };
        filter.doFilter(req, res, chain);
        return new Object[]{ user.get(), res.getStatus(), res, attr.get() };
    }

    @Test void validHeaderTokenAuthenticatesAsPrimaryUser() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, USER);
        var req = new MockHttpServletRequest("POST", "/api/settings/agents/gropar/definition/reset");
        req.addHeader(LocalAccessFilter.HEADER, TOKEN);
        var r = run(filter, req);
        assertThat(r[0]).isEqualTo(USER);
        assertThat(r[3]).isEqualTo(Boolean.TRUE);
        assertThat(r[1]).isEqualTo(200);
    }

    @Test void validCookieTokenAuthenticates() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, USER);
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.setCookies(new Cookie(LocalAccessFilter.COOKIE, TOKEN));
        var r = run(filter, req);
        assertThat(r[0]).isEqualTo(USER);
        assertThat(r[3]).isEqualTo(Boolean.TRUE);
    }

    @Test void queryParamTokenSetsCookieAndRedirectsWithoutLat() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, USER);
        var req = new MockHttpServletRequest("GET", "/");
        req.setQueryString("lat=" + TOKEN);
        req.setParameter("lat", TOKEN);
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {});
        assertThat(res.getStatus()).isEqualTo(302);
        assertThat(res.getRedirectedUrl()).isEqualTo("/");
        Cookie c = res.getCookie(LocalAccessFilter.COOKIE);
        assertThat(c).isNotNull();
        assertThat(c.getValue()).isEqualTo(TOKEN);
        assertThat(c.isHttpOnly()).isTrue();
    }

    @Test void wrongTokenDoesNotBypass() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, USER);
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader(LocalAccessFilter.HEADER, "WRONG");
        var r = run(filter, req);
        assertThat(r[3]).isNull();
        assertThat(r[0]).isEqualTo("default");
    }

    @Test void disabledFlagIsPassThroughEvenWithToken() throws Exception {
        var filter = new LocalAccessFilter(false, TOKEN, USER);
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader(LocalAccessFilter.HEADER, TOKEN);
        var r = run(filter, req);
        assertThat(r[3]).isNull();
        assertThat(r[0]).isEqualTo("default");
    }

    @Test void blankTokenWithFlagOnIsDisabled() throws Exception {
        var filter = new LocalAccessFilter(true, "  ", USER);
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader(LocalAccessFilter.HEADER, "  ");
        var r = run(filter, req);
        assertThat(r[3]).isNull();
    }

    @Test void excludedWebhookPathIsNotIntercepted() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, USER);
        var req = new MockHttpServletRequest("POST", "/api/gropar/complete");
        req.addHeader(LocalAccessFilter.HEADER, TOKEN);
        var r = run(filter, req);
        assertThat(r[3]).isNull();
    }

    @Test void blankPrimaryUserFallsBackToDefault() throws Exception {
        var filter = new LocalAccessFilter(true, TOKEN, "");
        var req = new MockHttpServletRequest("GET", "/api/watchlist");
        req.addHeader(LocalAccessFilter.HEADER, TOKEN);
        var r = run(filter, req);
        assertThat(r[0]).isEqualTo("default");
        assertThat(r[3]).isEqualTo(Boolean.TRUE);
    }
}
