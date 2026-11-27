package dev.chatconnect.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * I14 for the connect phase, with the shipped 1 s connect-timeout. The upstream is a listening
 * socket that never accepts and whose accept queue is full: Linux then drops new SYNs, so the
 * gateway's connect hangs until its own deadline. This is deterministic, unlike "unroutable" IPs,
 * whose behaviour depends on the network. A live chat stub warms the gateway's HTTP client first,
 * so the timing measures the connect deadline, not first-request class loading.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayConnectTimeoutTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1000); // application.yml
    private static ServerSocket blackhole;
    private static HttpServer liveStub;
    private static final List<Socket> queueFillers = new ArrayList<>();

    @Autowired
    WebTestClient web;

    @BeforeAll
    static void fillTheAcceptQueue() throws IOException {
        liveStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        liveStub.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        liveStub.start();
        blackhole = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); // backlog 1, never accept()
        boolean synsDropped = false;
        for (int i = 0; i < 8 && !synsDropped; i++) {
            Socket filler = new Socket();
            try {
                filler.connect(blackhole.getLocalSocketAddress(), 300);
                queueFillers.add(filler);
            } catch (SocketTimeoutException expectedOnceFull) {
                filler.close();
                synsDropped = true; // the precondition, verified rather than assumed
            }
        }
        assumeTrue(synsDropped, "kernel did not drop SYNs on a full accept queue; cannot simulate a hung connect");
    }

    @AfterAll
    static void release() throws IOException {
        for (Socket filler : queueFillers) {
            filler.close();
        }
        blackhole.close();
        liveStub.stop(0);
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URI", () -> "http://127.0.0.1:" + blackhole.getLocalPort());
        registry.add("CHAT_SERVICE_URI", () -> "http://127.0.0.1:" + liveStub.getAddress().getPort());
    }

    @Test
    void hungConnectFailsAtTheConnectTimeoutAs503Problem() throws IOException {
        web.get().uri("/api/chat/v1/warm-up").exchange().expectStatus().isNoContent();

        long start = System.nanoTime();
        EntityExchangeResult<String> response = web.get().uri("/api/auth/v1/ping")
                .exchange()
                .expectBody(String.class)
                .returnResult();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode body = new ObjectMapper().readTree(response.getResponseBody());
        assertThat(body.path("type").asText()).isEqualTo("urn:chatconnect:problem:upstream-unavailable");
        // Lower bound: the connect really hung until the deadline. Upper bound: one attempt, no retry
        // (a retried connect would take ≥ 2 × CONNECT_TIMEOUT).
        assertThat(elapsed).isBetween(CONNECT_TIMEOUT.minusMillis(50), CONNECT_TIMEOUT.plusMillis(500));
    }
}
