package dev.chatconnect.gateway.observability;

import dev.chatconnect.gateway.error.GatewayProblem;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the W3C trace-id of the server span on every response as {@value #TRACE_ID_HEADER}, and writes
 * one ECS access-log line per request (ADR-003). It is the reactive twin of the MVC filter in
 * auth-service and chat-service.
 *
 * <p>WebFlux starts the server observation when the handler chain is <em>subscribed</em>, after this
 * method has assembled it. So the observation is read from the Reactor context inside
 * {@link Mono#deferContextual}, and the log line is written inside that observation's scope so that
 * the MDC holds traceId/spanId.
 *
 * <p>It logs method, path (never the query string), status and duration only. Probe paths are not
 * logged.
 */
@Component
public class RequestTraceFilter implements WebFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** Exchange attribute holding the trace-id, read by the problem+json error handler. */
    public static final String TRACE_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
    /** nginx convention: the client went away before the response completed. */
    static final int CLIENT_CLOSED_REQUEST = 499;
    private static final String PROBE_PATH_PREFIX = "/actuator/health";
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    private final Tracer tracer;

    public RequestTraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public int getOrder() {
        // Before Spring Security's WebFilterChainProxy (-100, P2), so 401/403 carry the header too.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.deferContextual(context -> {
            Observation observation = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            String traceId = traceIdOf(observation);
            if (traceId != null) {
                exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
                // At commit, so the edge value wins over a downstream X-Trace-Id copied into a
                // proxied response, and survives error handlers rewriting the response.
                exchange.getResponse().beforeCommit(() -> {
                    exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
                    return Mono.empty();
                });
            }
            long startNanos = System.nanoTime();
            return chain.filter(exchange)
                    .doOnSuccess(ignored -> logCompletion(exchange, observation, statusOf(exchange), startNanos))
                    .doOnError(error -> logCompletion(
                            exchange, observation, GatewayProblem.of(error).status().value(), startNanos))
                    .doOnCancel(() -> logCompletion(exchange, observation, CLIENT_CLOSED_REQUEST, startNanos));
        });
    }

    private String traceIdOf(Observation observation) {
        if (observation == null) {
            return null;
        }
        try (Observation.Scope ignored = observation.openScope()) {
            Span span = tracer.currentSpan();
            return span == null || span.context().traceId().isEmpty() ? null : span.context().traceId();
        }
    }

    private static int statusOf(ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return status == null ? 200 : status.value();
    }

    private void logCompletion(ServerWebExchange exchange, Observation observation, int status, long startNanos) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(PROBE_PATH_PREFIX)) {
            return;
        }
        Runnable write = () -> log.atInfo()
                .addKeyValue("http.request.method", exchange.getRequest().getMethod().name())
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("event.duration", System.nanoTime() - startNanos)
                .log("request completed");
        if (observation == null) {
            write.run();
            return;
        }
        try (Observation.Scope ignored = observation.openScope()) {
            write.run();
        }
    }
}
