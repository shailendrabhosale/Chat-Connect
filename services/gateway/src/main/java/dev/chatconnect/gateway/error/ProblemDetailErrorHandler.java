package dev.chatconnect.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chatconnect.gateway.observability.RequestTraceFilter;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders every error the gateway itself produces as RFC 7807 {@code application/problem+json}
 * with a {@code traceId} member. It never echoes exception messages, which carry internal
 * host:port. Responses from downstream services, including their errors, pass through untouched.
 *
 * <p>A downstream can fail after its headers were copied onto the response but before the first
 * body byte (nothing is committed yet). Its representation headers (Content-Encoding, ETag,
 * Cache-Control, ...) must not decorate the problem body, so they're dropped, and gateway errors
 * are never cacheable.
 */
@Component
@Order(-2) // ahead of Boot's DefaultErrorWebExceptionHandler (-1)
public class ProblemDetailErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailErrorHandler.class);

    private final ObjectMapper json;

    public ProblemDetailErrorHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(error); // status and headers already sent; nothing safe to rewrite
        }
        GatewayProblem problem = GatewayProblem.of(error);
        logFailure(exchange, problem, error);

        ProblemDetail body = ProblemDetail.forStatus(problem.status());
        body.setType(URI.create(problem.type()));
        body.setTitle(problem.title());
        URI instance = pathAsUri(exchange.getRequest().getPath().value());
        if (instance != null) {
            body.setInstance(instance);
        }
        String traceId = exchange.getAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE);
        if (traceId != null) {
            body.setProperty("traceId", traceId);
        }

        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
        response.setStatusCode(problem.status());
        HttpHeaders headers = response.getHeaders();
        headers.clearContentHeaders();
        headers.remove(HttpHeaders.ETAG);
        headers.remove(HttpHeaders.LAST_MODIFIED);
        headers.setCacheControl(CacheControl.noStore());
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setContentLength(bytes.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private static void logFailure(ServerWebExchange exchange, GatewayProblem problem, Throwable error) {
        if (problem.upstream()) {
            // Expected operational event, not a gateway bug: one line, no stack trace.
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            log.atWarn()
                    .addKeyValue("route.id", route == null ? "unknown" : route.getId())
                    .addKeyValue("error.type", error.getClass().getName())
                    .log("upstream failure: {}", problem.slug());
        } else if (problem.status().is5xxServerError()) {
            log.error("unhandled gateway error", error);
        }
        // 4xx: the access log line is enough.
    }

    private static URI pathAsUri(String path) {
        try {
            return new URI(null, null, path, null); // quotes characters that are illegal in a URI
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
