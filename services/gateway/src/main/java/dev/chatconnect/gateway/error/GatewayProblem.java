package dev.chatconnect.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.ErrorResponse;
import reactor.netty.http.client.PrematureCloseException;

/**
 * Maps a failure inside the gateway to the status and RFC 7807 problem type the client sees
 * (ADR-023). The error handler and the access log both use it, so the logged status always
 * equals the returned one.
 *
 * @param upstream true when the cause is a downstream service, not the gateway itself
 */
public record GatewayProblem(HttpStatusCode status, String slug, String title, boolean upstream) {

    public static final String TYPE_PREFIX = "urn:chatconnect:problem:";
    private static final int MAX_CAUSE_DEPTH = 10;

    static final GatewayProblem UPSTREAM_UNAVAILABLE = new GatewayProblem(
            HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable", "Upstream service unavailable", true);
    static final GatewayProblem UPSTREAM_TIMEOUT = new GatewayProblem(
            HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout", "Upstream service timed out", true);
    static final GatewayProblem UPSTREAM_BAD_RESPONSE = new GatewayProblem(
            HttpStatus.BAD_GATEWAY, "upstream-bad-response", "Upstream service closed the connection", true);
    static final GatewayProblem INTERNAL = new GatewayProblem(
            HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error", false);

    public static GatewayProblem of(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            // Refused or connect timeout (Netty's ConnectTimeoutException is a ConnectException), or
            // the name no longer resolves: Docker DNS drops a stopped container's hostname.
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return UPSTREAM_UNAVAILABLE;
            }
            // Downstream closed a (pooled) connection before answering.
            if (cause instanceof PrematureCloseException) {
                return UPSTREAM_BAD_RESPONSE;
            }
        }
        if (error instanceof ErrorResponse response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return UPSTREAM_TIMEOUT; // gateway response-timeout elapsed
            }
            return generic(status);
        }
        return INTERNAL;
    }

    private static GatewayProblem generic(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String slug = known == null ? "http-" + status.value() : known.name().toLowerCase(Locale.ROOT).replace('_', '-');
        String title = known == null ? "HTTP " + status.value() : known.getReasonPhrase();
        return new GatewayProblem(status, slug, title, false);
    }

    public String type() {
        return TYPE_PREFIX + slug;
    }
}
