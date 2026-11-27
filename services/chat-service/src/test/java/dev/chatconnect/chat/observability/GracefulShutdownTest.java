package dev.chatconnect.chat.observability;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.chatconnect.chat.ChatServiceApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * I10 graceful shutdown, proven against a real server: on close, readiness flips to REFUSING_TRAFFIC,
 * new connections are refused, and close() blocks until a request already in flight has completed.
 * The last point is what fails under {@code server.shutdown=immediate}: in-JVM the connection may
 * survive, but in a container the process exits as soon as close() returns.
 */
class GracefulShutdownTest {

    private static final Duration IN_FLIGHT_DURATION = Duration.ofSeconds(4);

    @Test
    void inFlightRequestCompletesWhileNewRequestsAreRefused() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        List<ReadinessState> readiness = new CopyOnWriteArrayList<>();
        ConfigurableApplicationContext app = new SpringApplicationBuilder(ChatServiceApplication.class)
                // Functional bean, not a @RestController: test classes sit inside the component-scan root.
                .initializers(ctx -> ((GenericApplicationContext) ctx)
                        .registerBean("slowRoute", RouterFunction.class, () -> slowRoute(entered)))
                .listeners((ApplicationListener<ApplicationEvent>) event -> {
                    if (event instanceof AvailabilityChangeEvent<?> change
                            && change.getState() instanceof ReadinessState state) {
                        readiness.add(state);
                    }
                })
                // A command-line arg, not .properties(): builder properties are *defaults* and lose to
                // application.yml's ${SERVER_PORT:808x}, which made this bind the real port.
                .run("--server.port=0");
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();

        CompletableFuture<HttpResponse<String>> inFlight = HttpClient.newHttpClient()
                .sendAsync(get(port, "/test/slow", Duration.ofSeconds(10)), HttpResponse.BodyHandlers.ofString());
        assertThat(entered.await(5, SECONDS)).as("slow request reached the handler").isTrue();

        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(app::close);

        assertThat(newRequestsRefusedWithin(port, Duration.ofSeconds(1))).as("new requests refused").isTrue();
        assertThat(inFlight).as("in-flight request still running when refusal observed").isNotDone();

        // Graceful: close() returns only after in-flight requests drained, so the response is already
        // here. Immediate: close() returns early (or the connection is cut) and this times out or fails.
        shutdown.get(15, SECONDS);
        HttpResponse<String> response = inFlight.get(500, MILLISECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("done");
        assertThat(readiness).containsSubsequence(ReadinessState.ACCEPTING_TRAFFIC, ReadinessState.REFUSING_TRAFFIC);
    }

    private static RouterFunction<ServerResponse> slowRoute(CountDownLatch entered) {
        return RouterFunctions.route()
                .GET("/test/slow", request -> {
                    entered.countDown();
                    Thread.sleep(IN_FLIGHT_DURATION.toMillis());
                    return ServerResponse.ok().body("done");
                })
                .build();
    }

    /** Fresh client per attempt, so no pooled connection can sneak a request through. */
    private static boolean newRequestsRefusedWithin(int port, Duration window) throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> probe = HttpClient.newHttpClient()
                        .send(get(port, "/actuator/health/readiness", Duration.ofMillis(300)),
                                HttpResponse.BodyHandlers.discarding());
                if (probe.statusCode() == 503) {
                    return true;
                }
            } catch (IOException refusedOrTimedOut) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static HttpRequest get(int port, String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(timeout).GET().build();
    }
}
