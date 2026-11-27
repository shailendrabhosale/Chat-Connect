package dev.chatconnect.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.netty.channel.ConnectTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.PrematureCloseException;

/** The failure → status → problem-type table of ADR-023, one row per case. */
class GatewayProblemTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void mapsFailureToStatusAndType(String name, Throwable error, int status, String slug, boolean upstream) {
        GatewayProblem problem = GatewayProblem.of(error);

        assertThat(problem.status().value()).isEqualTo(status);
        assertThat(problem.slug()).isEqualTo(slug);
        assertThat(problem.type()).isEqualTo("urn:chatconnect:problem:" + slug);
        assertThat(problem.upstream()).isEqualTo(upstream);
        assertThat(problem.title()).isNotBlank();
    }

    static Stream<Arguments> mapsFailureToStatusAndType() {
        return Stream.of(
                arguments("connection refused", new ConnectException("Connection refused"),
                        503, "upstream-unavailable", true),
                arguments("connect timeout", new ConnectTimeoutException("connection timed out after 1000 ms"),
                        503, "upstream-unavailable", true),
                arguments("name does not resolve (stopped container)", new UnknownHostException("chat-service"),
                        503, "upstream-unavailable", true),
                arguments("wrapped connect failure", new IllegalStateException("wrapper", new ConnectException("x")),
                        503, "upstream-unavailable", true),
                arguments("premature close", PrematureCloseException.TEST_EXCEPTION,
                        502, "upstream-bad-response", true),
                arguments("response timeout", new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "took too long"),
                        504, "upstream-timeout", true),
                arguments("no route", new ResponseStatusException(HttpStatus.NOT_FOUND),
                        404, "not-found", false),
                arguments("method not allowed", new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED),
                        405, "method-not-allowed", false),
                arguments("non-standard status", new ResponseStatusException(HttpStatusCode.valueOf(599)),
                        599, "http-599", false),
                arguments("gateway bug", new IllegalStateException("bug"),
                        500, "internal-error", false));
    }

    @Test
    void causeCycleTerminates() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second); // first → second → first → ...

        assertThat(GatewayProblem.of(first)).isEqualTo(GatewayProblem.INTERNAL);
    }
}
