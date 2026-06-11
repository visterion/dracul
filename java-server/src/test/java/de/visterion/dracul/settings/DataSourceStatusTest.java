package de.visterion.dracul.settings;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceStatusTest {

    @Test void ok2xx() {
        assertThat(DataSourceStatus.classify(200, null)).isEqualTo("ok");
        assertThat(DataSourceStatus.classify(204, null)).isEqualTo("ok");
    }

    @Test void rateLimited429() {
        assertThat(DataSourceStatus.classify(429, null)).isEqualTo("rate_limited");
    }

    @Test void clientAndServerErrors() {
        assertThat(DataSourceStatus.classify(401, null)).isEqualTo("error");
        assertThat(DataSourceStatus.classify(403, null)).isEqualTo("error");
        assertThat(DataSourceStatus.classify(500, null)).isEqualTo("error");
    }

    @Test void timeoutException() {
        assertThat(DataSourceStatus.classify(null, new TimeoutException("slow"))).isEqualTo("timeout");
        assertThat(DataSourceStatus.classify(null, new RuntimeException(new TimeoutException())))
                .isEqualTo("timeout");
    }

    @Test void socketTimeoutIsTimeout() {
        assertThat(DataSourceStatus.classify(null, new SocketTimeoutException("read timed out")))
                .isEqualTo("timeout");
        assertThat(DataSourceStatus.classify(null, new RuntimeException(new SocketTimeoutException())))
                .isEqualTo("timeout");
    }

    @Test void httpTimeoutIsTimeout() {
        assertThat(DataSourceStatus.classify(null, new HttpTimeoutException("request timed out")))
                .isEqualTo("timeout");
    }

    @Test void otherExceptionIsError() {
        assertThat(DataSourceStatus.classify(null, new IOException("boom"))).isEqualTo("error");
    }

    @Test void nullStatusNoErrorIsError() {
        assertThat(DataSourceStatus.classify(null, null)).isEqualTo("error");
    }
}
