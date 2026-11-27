package dev.chatconnect.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.chatconnect.gateway.observability.RequestTraceFilter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P1.C3: static routes, unchanged paths, trace propagation to the downstream hop, timeouts, and
 * RFC 7807 errors for failures the gateway itself produces. Downstreams are JDK HttpServer stubs.
 * chat → a live stub; auth → a closed port (upstream down).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Short timeout keeps the test fast; the shipped 5 s value is asserted in GatewayApplicationTest.
        properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=500ms")
@AutoConfigureObservability(metrics = false)
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Duration SLOW_UPSTREAM = Duration.ofSeconds(2);

    private static HttpServer chatStub;
    private static ExecutorService stubThreads;
    private static int deadPort;
    /** Last request the stub saw, keyed by path. */
    private static final ConcurrentHashMap<String, Seen> seen = new ConcurrentHashMap<>();

    record Seen(String rawQuery, Headers headers) {
    }

    @Autowired
    WebTestClient web;

    @BeforeAll
    static void startStubs() throws IOException {
        chatStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubThreads = Executors.newCachedThreadPool();
        chatStub.setExecutor(stubThreads);
        chatStub.createContext("/", GatewayRoutingTest::handleChat);
        chatStub.start();
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort(); // free now, closed when the test runs → connection refused
        }
    }

    @AfterAll
    static void stopStubs() {
        chatStub.stop(0);
        stubThreads.shutdownNow();
    }

    @BeforeEach
    void forgetEarlierRequests() {
        seen.clear(); // so no test can pass on a request an earlier test made
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("CHAT_SERVICE_URI", () -> "http://127.0.0.1:" + chatStub.getAddress().getPort());
        registry.add("AUTH_SERVICE_URI", () -> "http://127.0.0.1:" + deadPort);
    }

    @Test
    void chatRouteForwardsPathAndQueryUnchanged() {
        EntityExchangeResult<String> response = get("/api/chat/v1/ping?x=1");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.getResponseBody()).isEqualTo("{\"service\":\"stub-chat\"}");
        assertThat(seen.get("/api/chat/v1/ping").rawQuery()).isEqualTo("x=1");
    }

    @Test
    void downstreamHopContinuesTheTraceAndTheEdgeHeaderWins() {
        EntityExchangeResult<String> response = get("/api/chat/v1/ping");

        String traceparent = seen.get("/api/chat/v1/ping").headers().getFirst("traceparent");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        // The stub answers with a bogus X-Trace-Id; the client must see exactly one value: the trace's.
        assertThat(response.getResponseHeaders().get(RequestTraceFilter.TRACE_ID_HEADER))
                .containsExactly(traceparent.split("-")[1]);
    }

    @Test
    void clientTraceparentIsContinuedEndToEnd() {
        get("/api/chat/v1/ping", "traceparent", "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01");

        String[] hop = seen.get("/api/chat/v1/ping").headers().getFirst("traceparent").split("-");
        assertThat(hop[1]).isEqualTo(INBOUND_TRACE_ID);
        assertThat(hop[2]).as("gateway's client span is the new parent").isNotEqualTo("00f067aa0ba902b7");
    }

    @Test
    void upstreamDownIsFast503ProblemWithoutInternalAddresses(CapturedOutput output) throws Exception {
        long start = System.nanoTime();
        EntityExchangeResult<String> response = get("/api/auth/v1/ping");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable", "/api/auth/v1/ping");
        // Refused connects fail at once; the 1 s connect-timeout path is GatewayConnectTimeoutTest.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(response.getResponseBody()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(deadPort));

        String traceId = traceIdOf(response);
        JsonNode warn = awaitLine(output, l -> "WARN".equals(l.at("/log/level").asText())
                && traceId.equals(l.path("traceId").asText()));
        assertThat(warn.path("message").asText()).isEqualTo("upstream failure: upstream-unavailable");
        assertThat(warn.at("/route/id").asText()).isEqualTo("auth-service");
        assertThat(warn.at("/error/stack_trace").isMissingNode()).as("upstream failures log no stack trace").isTrue();
        JsonNode access = awaitLine(output, l -> "request completed".equals(l.path("message").asText())
                && traceId.equals(l.path("traceId").asText()));
        assertThat(access.at("/http/response/status_code").asInt()).as("access log = returned status").isEqualTo(503);
    }

    @Test
    void slowUpstreamIs504ProblemAtTheResponseTimeout() {
        long start = System.nanoTime();
        EntityExchangeResult<String> response = get("/api/chat/v1/slow");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertProblem(response, HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout", "/api/chat/v1/slow");
        assertThat(elapsed).isBetween(Duration.ofMillis(450), SLOW_UPSTREAM.minusMillis(500));
    }

    @Test
    void downstreamErrorsPassThroughUntouched() {
        EntityExchangeResult<String> response = get("/api/chat/v1/boom");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getResponseBody()).isEqualTo("{\"type\":\"urn:chatconnect:problem:from-service\"}");
    }

    @Test
    void downstreamDyingAfterItsHeadersIs502WithoutItsRepresentationHeaders() {
        EntityExchangeResult<String> response = get("/api/chat/v1/truncated");

        assertProblem(response, HttpStatus.BAD_GATEWAY, "upstream-bad-response", "/api/chat/v1/truncated");
        HttpHeaders headers = response.getResponseHeaders();
        assertThat(headers.containsKey(HttpHeaders.CONTENT_ENCODING)).as("would make clients gunzip plain JSON").isFalse();
        assertThat(headers.containsKey(HttpHeaders.ETAG)).isFalse();
        assertThat(headers.containsKey(HttpHeaders.LAST_MODIFIED)).isFalse();
    }

    @Test
    void unknownRouteIs404Problem() {
        assertProblem(get("/api/nope/v1/x"), HttpStatus.NOT_FOUND, "not-found", "/api/nope/v1/x");
    }

    @Test
    void routesMatchOnTheServiceSegmentOnly() {
        // "/api/chatty" must not hit the chat route (Path=/api/chat/** matches whole segments).
        assertProblem(get("/api/chatty/v1/ping"), HttpStatus.NOT_FOUND, "not-found", "/api/chatty/v1/ping");
    }

    private void assertProblem(EntityExchangeResult<String> response, HttpStatus status, String slug, String path) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        JsonNode body = parse(response.getResponseBody());
        assertThat(body.path("type").asText()).isEqualTo("urn:chatconnect:problem:" + slug);
        assertThat(body.path("status").asInt()).isEqualTo(status.value());
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("instance").asText()).isEqualTo(path);
        assertThat(body.path("traceId").asText()).isEqualTo(traceIdOf(response)).matches("[0-9a-f]{32}");
        assertThat(body.has("detail")).as("no exception text leaks").isFalse();
        assertThat(response.getResponseHeaders().getCacheControl()).as("gateway errors are never cached").isEqualTo("no-store");
    }

    private EntityExchangeResult<String> get(String path, String... headerPairs) {
        return web.get().uri(path)
                .headers(headers -> {
                    for (int i = 0; i < headerPairs.length; i += 2) {
                        headers.add(headerPairs[i], headerPairs[i + 1]);
                    }
                })
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private static String traceIdOf(EntityExchangeResult<?> response) {
        return response.getResponseHeaders().getFirst(RequestTraceFilter.TRACE_ID_HEADER);
    }

    private static void handleChat(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        seen.put(path, new Seen(exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders()));
        switch (path) {
            case "/api/chat/v1/ping" -> {
                exchange.getResponseHeaders().add(RequestTraceFilter.TRACE_ID_HEADER, "bogus");
                respond(exchange, 200, "application/json", "{\"service\":\"stub-chat\"}");
            }
            case "/api/chat/v1/slow" -> {
                sleep(SLOW_UPSTREAM);
                respond(exchange, 200, "application/json", "{}");
            }
            case "/api/chat/v1/truncated" -> {
                // Headers go out, then the connection dies before the promised 100 body bytes.
                Headers out = exchange.getResponseHeaders();
                out.add("Content-Type", "application/json");
                out.add("Content-Encoding", "gzip");
                out.add("Cache-Control", "max-age=3600");
                out.add("ETag", "\"v1\"");
                out.add("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT");
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().flush();
                exchange.close(); // short body → the stub drops the connection
            }
            case "/api/chat/v1/boom" ->
                    respond(exchange, 409, "application/problem+json", "{\"type\":\"urn:chatconnect:problem:from-service\"}");
            default -> respond(exchange, 404, "text/plain", "stub: no such path");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException clientGone) {
            // the gateway gave up (timeout test); nothing to deliver
        } finally {
            exchange.close();
        }
    }

    private static JsonNode awaitLine(CapturedOutput output, java.util.function.Predicate<JsonNode> match) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<JsonNode> found = output.getOut().lines()
                    .filter(l -> l.startsWith("{"))
                    .map(GatewayRoutingTest::parse)
                    .filter(match)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("no matching log line in:\n" + output.getOut());
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
