package dev.chatconnect.auth.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the W3C trace-id of the server span on every response as {@value #TRACE_ID_HEADER}, and writes
 * one ECS access-log line per request (ADR-003).
 *
 * <p>Ordering: it runs after Boot's {@code ServerHttpObservationFilter} ({@code HIGHEST_PRECEDENCE + 1}),
 * which opens the span and fills the MDC, and before Spring Security ({@code -100}, P2), so 401/403
 * responses carry the header too.
 *
 * <p>It logs method, path (never the query string), status and duration only: no headers, bodies or
 * tokens. Health-probe paths are not logged, because probes would flood the logs.
 */
@Component
@Order(RequestTraceFilter.ORDER)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final String PROBE_PATH_PREFIX = "/actuator/health";
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    private final Tracer tracer;

    public RequestTraceFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Span span = tracer.currentSpan();
        if (span != null && !span.context().traceId().isEmpty()) {
            response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        }
        long startNanos = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // The container turns an escaped exception into a 500 after this filter returns.
            failed = true;
            throw e;
        } finally {
            if (!request.getRequestURI().startsWith(PROBE_PATH_PREFIX)) {
                log.atInfo()
                        .addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("url.path", request.getRequestURI())
                        .addKeyValue("http.response.status_code", failed ? 500 : response.getStatus())
                        .addKeyValue("event.duration", System.nanoTime() - startNanos)
                        .log("request completed");
            }
        }
    }
}
