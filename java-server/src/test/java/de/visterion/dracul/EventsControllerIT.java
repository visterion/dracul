package de.visterion.dracul;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ContainerConfig.class)
@ActiveProfiles("dev")
class EventsControllerIT {

    @LocalServerPort int port;

    @Test
    void streamReturnsEventStreamContentType() throws Exception {
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/events"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();

        HttpResponse<Flow.Publisher<List<ByteBuffer>>> resp =
                client.send(req, HttpResponse.BodyHandlers.ofPublisher());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");

        resp.body().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.cancel(); }
            public void onNext(List<ByteBuffer> item) { }
            public void onError(Throwable t) { }
            public void onComplete() { }
        });
    }
}
