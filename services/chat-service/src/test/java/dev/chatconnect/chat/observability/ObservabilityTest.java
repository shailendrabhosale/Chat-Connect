package dev.chatconnect.chat.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** P1.C2 / I10: probes, actuator exposure, W3C trace-id correlation, ECS JSON request logs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(metrics = false) // Boot's test support swaps in a no-op tracer unless asked
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

    private static final String SERVICE = "chat-service";
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String INBOUND_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate http;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void probesAreUp(String path) {
        ResponseEntity<String> response = http.getForEntity(path, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump"})
    void onlyHealthIsExposed(String path) {
        assertThat(http.getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void errorResponsesCarryAGeneratedTraceId() {
        ResponseEntity<String> response = get("/no-such-resource");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(traceIdOf(response)).matches(TRACE_ID);
    }

    @Test
    void inboundW3cTraceparentIsContinued() {
        ResponseEntity<String> response =
                get("/no-such-resource", "traceparent", "00-" + INBOUND_TRACE_ID + "-00f067aa0ba902b7-01");

        assertThat(traceIdOf(response)).isEqualTo(INBOUND_TRACE_ID);
    }

    @Test
    void b3HeadersAreIgnored() {
        ResponseEntity<String> multi = get("/no-such-resource",
                "X-B3-TraceId", INBOUND_TRACE_ID, "X-B3-SpanId", "00f067aa0ba902b7", "X-B3-Sampled", "1");
        ResponseEntity<String> single = get("/no-such-resource", "b3", INBOUND_TRACE_ID + "-00f067aa0ba902b7-1");

        assertThat(traceIdOf(multi)).matches(TRACE_ID).isNotEqualTo(INBOUND_TRACE_ID);
        assertThat(traceIdOf(single)).matches(TRACE_ID).isNotEqualTo(INBOUND_TRACE_ID);
    }

    @Test
    void requestLogLineIsEcsJsonWithTheResponseTraceId(CapturedOutput output) {
        int mark = output.getOut().length();
        String traceId = traceIdOf(get("/no-such-resource"));

        JsonNode line = awaitRequestLine(output, mark, traceId);

        assertThat(line.at("/service/name").asText()).isEqualTo(SERVICE);
        assertThat(line.at("/ecs/version").asText()).isNotEmpty();
        assertThat(line.at("/log/level").asText()).isEqualTo("INFO");
        assertThat(line.path("spanId").asText()).matches("[0-9a-f]{16}");
        assertThat(line.at("/http/request/method").asText()).isEqualTo("GET");
        assertThat(line.at("/url/path").asText()).isEqualTo("/no-such-resource");
        assertThat(line.at("/http/response/status_code").asInt()).isEqualTo(404);
        assertThat(line.at("/event/duration").asLong()).isPositive();
    }

    @Test
    void everyLineIsJsonAndEveryRequestLineCarriesATraceId(CapturedOutput output) {
        int mark = output.getOut().length();
        String last = null;
        for (int i = 0; i < 5; i++) {
            last = traceIdOf(get("/no-such-resource-" + i));
        }
        awaitRequestLine(output, mark, last);

        List<JsonNode> lines = jsonLines(output, mark); // fails on any non-JSON line
        assertThat(lines).filteredOn(l -> l.at("/log/logger").asText().endsWith("RequestTraceFilter"))
                .hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(l -> assertThat(l.path("traceId").asText()).matches(TRACE_ID));
    }

    @Test
    void queryStringIsNeverLogged(CapturedOutput output) {
        int mark = output.getOut().length();
        String traceId = traceIdOf(get("/no-such-resource?token=secret-value"));

        JsonNode line = awaitRequestLine(output, mark, traceId);

        assertThat(line.at("/url/path").asText()).isEqualTo("/no-such-resource");
        assertThat(output.getOut().substring(mark)).doesNotContain("secret-value");
    }

    @Test
    void probesAreNotAccessLogged(CapturedOutput output) {
        int mark = output.getOut().length();
        http.getForEntity("/actuator/health/readiness", String.class);
        awaitRequestLine(output, mark, traceIdOf(get("/after-probe")));

        assertThat(jsonLines(output, mark)).noneSatisfy(
                l -> assertThat(l.at("/url/path").asText()).startsWith("/actuator/health"));
    }

    private ResponseEntity<String> get(String path, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.add(headerPairs[i], headerPairs[i + 1]);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String traceIdOf(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(RequestTraceFilter.TRACE_ID_HEADER);
    }

    /** The line is written after the response is flushed, so poll for it. */
    private static JsonNode awaitRequestLine(CapturedOutput output, int mark, String traceId) {
        Predicate<JsonNode> match = l -> "request completed".equals(l.path("message").asText())
                && traceId.equals(l.path("traceId").asText());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<JsonNode> found = jsonLines(output, mark).stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("no request line for traceId " + traceId + " in:\n" + output.getOut().substring(mark));
    }

    private static List<JsonNode> jsonLines(CapturedOutput output, int mark) {
        return output.getOut().substring(mark).lines().filter(l -> !l.isBlank()).map(ObservabilityTest::parse).toList();
    }

    private static JsonNode parse(String line) {
        try {
            JsonNode node = JSON.readTree(line);
            if (!node.isObject()) {
                throw new AssertionError("log line is not a JSON object: " + line);
            }
            return node;
        } catch (Exception e) {
            throw new AssertionError("log line is not JSON: " + line, e);
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
