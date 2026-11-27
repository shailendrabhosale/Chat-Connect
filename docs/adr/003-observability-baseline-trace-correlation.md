# ADR-003: Observability baseline, W3C trace-id correlation from day one

- **Status:** Accepted · 2026-11-27 · P1.C2
- **Resolves (MP §7):** Tracing in V4 vs correlation IDs day one. Implements I10 for gateway, auth-service and chat-service.

## Context
Distributed tracing (Jaeger, P20) arrives in V4, but every log line needs a correlation ID from the first service commit, or retrofitting it never happens. The ID must survive HTTP now, and later gRPC, Kafka headers, outbox rows and pub/sub envelopes (I5, MP §6).

## Options
1. **Custom `X-Correlation-Id`** minted at the gateway and copied through an MDC filter. Simple, but it's a second ID scheme: it needs bridging to W3C at P20 and manual propagation on every new transport.
2. **Micrometer Tracing + Brave bridge.** Mature, but P20 exports OTLP, which is a detour for Brave.
3. **Micrometer Tracing + OpenTelemetry bridge, no exporter until P20** (chosen).

## Decision
- **The ID is the W3C trace-id** (`micrometer-tracing-bridge-otel`). `management.tracing.propagation.type=w3c`: only `traceparent` is consumed and produced, and B3 headers are ignored (tested).
- **Sampling** defaults to `1.0` (`TRACING_SAMPLING_PROBABILITY`). Trace IDs are generated, logged and returned **even when unsampled** (verified with probability `0.0`), so correlation never depends on sampling. P20 sets 10% in prod.
- **`X-Trace-Id` response header** on every response, including 404s and errors. It is output only; the input is `traceparent`. One filter per service (MVC `OncePerRequestFilter`, WebFlux `WebFilter`) at `HIGHEST_PRECEDENCE + 10`: after Boot's observation filter (`+1`), before Spring Security (`-100`, P2). On WebFlux the observation exists only at subscription, so the filter reads it from the Reactor context (`Mono.deferContextual`).
- **Logs:** Boot's built-in ECS structured console output (`logging.structured.format.console=ecs`, ECS 8.11), so no extra encoder. The banner turns off automatically. MDC keys land top-level as `traceId`/`spanId`, not ECS `trace.id`; if Kibana correlation needs the ECS names (P19), rename via `logging.structured.json.rename`. `service.environment` = `APP_ENV`, `service.node.name` = `HOSTNAME` (replica identity).
- **Access log:** one INFO line per request: `http.request.method`, `url.path`, `http.response.status_code`, `event.duration` (ns). It never includes the query string, headers or body (tested). Probe paths are skipped.
- **Probes:** `/actuator/health/liveness` (process only) and `/actuator/health/readiness` (critical dependencies as they arrive), enabled explicitly because they're off outside K8s. Only `health` is exposed and details are never shown. They're served on the app port, with no separate management port.
- **Graceful shutdown:** `server.shutdown=graceful` (the Boot 3.5 default, set explicitly) with `spring.lifecycle.timeout-per-shutdown-phase=20s`. On close, readiness goes to `REFUSING_TRAFFIC`, new connections are refused, and `close()` blocks until in-flight requests drain. Tested on Tomcat and Netty, and the test fails under `immediate`.
- **Gateway:** `spring.reactor.context-propagation=auto`, so MDC follows requests across Reactor/Netty threads.

## Consequences
- **+** One ID from browser to DB today. P20 only adds an exporter, with no change to the ID scheme.
- **−** Spans are created and dropped until P20 (CPU only). Automatic context propagation adds a ThreadLocal restore per Reactor operator in the gateway; it isn't measured, and P18/P20 should measure it.
- **Constraint for P1.C4:** compose `stop_grace_period` must be ≥ 25 s, because Docker's 10 s default would SIGKILL a 20 s drain. The ENTRYPOINT must be exec-form so the JVM receives SIGTERM.
- **Exposure:** the gateway's `/actuator/health` is reachable on the public port. It's details-free, and Caddy must block `/actuator/*` at P6.C2 (DEBT-3).
- **Failure mode:** an edge client can pick its own trace-id and force `sampled=1`. That's harmless without an exporter; P20 must decide whether the gateway re-roots or re-samples untrusted inbound traces.
- **Failure mode:** filter order regressions (for example, a filter placed before the observation filter) drop the header. The tests assert the header on every response type.
- **Cross-origin:** a SPA on another origin needs `Access-Control-Expose-Headers: X-Trace-Id` (P5.C4). Same-origin via Caddy or the Vite proxy doesn't.
- **MVC async:** the access line is written when the initial dispatch returns. No async endpoints exist in V1; revisit if one appears.
