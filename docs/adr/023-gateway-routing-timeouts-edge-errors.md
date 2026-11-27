# ADR-023: Gateway routing, timeouts and edge errors

- **Status:** Accepted · 2026-11-27 · P1.C3
- **Numbering:** MP §7 reserves 001–021 for named conflicts, and 022 is earmarked for the CLAUDE.md split (D3), so this takes the first free number.
- **Implements:** I14 at the edge; MP §0 RFC 7807 default for errors the gateway itself produces.

## Context
Spring Cloud Gateway 4.3.5 defaults, read from its configuration metadata and observed on the running jar:

| Default | Effect |
|---|---|
| `response-timeout` unset | waits forever for a downstream (I14 violation) |
| `connect-timeout` 30 s | a dead host stalls requests for 30 s |
| `pool.max-idle-time` unset | reuses connections Tomcat already closed (keep-alive 60 s) → "Connection prematurely closed" |
| downstream refused | **500** + ERROR stack trace; the exception message names the internal host:port |
| error bodies | Boot's `{timestamp,path,status,error,requestId}`: not RFC 7807, and carries no traceId |

## Options
1. Keep the defaults: unbounded waits, and upstream outages that look like gateway bugs. Rejected.
2. Retry at the gateway (SCG `Retry` filter for GETs): this amplifies load on a struggling service and duplicates the P7.C3 Resilience4j design (budgets, circuit breaker). Rejected for V1.
3. `StripPrefix` routes (`/api/chat/**` → `/**`): services would no longer own their public path, and version handling would move into the gateway. Rejected (architecture §8).
4. **Static routes with unchanged paths, explicit timeouts, no retries, and RFC 7807 edge errors with a single failure classifier** (chosen).

## Decision
- **Routes:** `Path=/api/auth/**` → `AUTH_SERVICE_URI` (default `http://localhost:8081`), `Path=/api/chat/**` → `CHAT_SERVICE_URI` (default `http://localhost:8082`). Paths and query strings are forwarded unchanged, and matching is per segment (`/api/chatty` does not match). Routes have no filters: no retries (POST isn't idempotent at the HTTP layer; I1 makes client replays safe) and no rewrites. Only `/api/{svc}/**` is routed, so service actuators are never reachable through the gateway. `/ws` joins at P4.C1 with its own timeout.
- **Timeouts:** connect **1000 ms**; response **5 s**, measured as time to response headers (`Flux.timeout` in `NettyRoutingFilter`); pool `max-idle-time` **15 s**, below Tomcat's keep-alive (unset falls back to `connectionTimeout` = 60 000 ms, read from tomcat-embed-core 10.1.55 bytecode).
- **Edge errors** are `application/problem+json` with `type` (`urn:chatconnect:problem:<slug>`), `title`, `status`, `instance` (the path) and `traceId`. There is **no `detail`**, because exception text carries internal addresses. They are sent with `Cache-Control: no-store`. If a downstream dies after its headers were copied but before the first body byte, its representation headers (`Content-Encoding`, `ETag`, `Last-Modified`, `Content-*`) are dropped (tested; without this, clients would try to gunzip plain JSON).

| Failure | Status | Slug | Log |
|---|---|---|---|
| connect refused, connect timeout, name doesn't resolve (a stopped container's Docker DNS name) | 503 | `upstream-unavailable` | WARN, route id + error type, no stack |
| response timeout | 504 | `upstream-timeout` | WARN |
| downstream closed the connection before or while answering | 502 | `upstream-bad-response` | WARN |
| Spring `ErrorResponse` (no route 404, 405, …) | its status | reason phrase, kebab-case | access line only |
| anything else | 500 | `internal-error` | ERROR + stack |

- 503 rather than 502 for refused connections: it means "temporary, retry later" and matches P7.C3's fail-fast 503. 502 is reserved for a downstream that answered badly.
- **Downstream responses, including their own problem bodies, pass through untouched.** The owning service authors its errors.
- **One classifier (`GatewayProblem`)** feeds both the response and the access log, so the logged status is always the returned one (tested).
- `X-Trace-Id` is set at **commit time**, so the edge value wins over a downstream copy and survives error rendering. That makes it exactly one header value.
- **Ping endpoints:** `GET /api/auth/v1/ping` and `GET /api/chat/v1/ping` return `{"service","status":"ok"}`. They're unauthenticated and give the P1.C3 AT and the P6.C3 smoke a 200 through the edge. They aren't probes; probes stay on `/actuator`.

## Evidence (2026-11-27, WSL2, local jars)
- **Hung connect** (accept queue full, so SYNs are dropped): 503 at ~1.03 s warm, one attempt. With `connect-timeout=3000` the test measures 3.07 s and fails. `GatewayConnectTimeoutTest`.
- **Slow upstream** (7 s): 504 at 5.003–5.045 s, measured gateway-side (`event.duration`) and confirmed by a monotonic wrapper.
- **Downstream stopped:** 503 in ~25 ms. **DNS failure:** 503, and the WARN shows `SearchDomainUnknownHostException`.
- **The same traceId** appears in the gateway and auth/chat access lines for proxied requests.

## Consequences
- **+** Every edge failure is bounded and classified. Clients can tell retry-later (503/504) from bugs (500), and nothing internal leaks.
- **−** A gateway timeout doesn't cancel downstream work: a service may still commit after the client got its 504. The client's retry must be idempotent (I1 for sends; every later write endpoint needs an idempotency story).
- **−** Only time-to-headers is bounded. A body that stalls after its headers is bounded by the client's deadline (browser 10 s), not the gateway's (DEBT-7, decide at P4.C1 with the `/ws` timeout).
- **−** SCG appends the client's `X-Forwarded-For` rather than replacing it. Anything keyed on client IP (P2.C2 login throttle, P15 rate limits) must take the right-most trusted hop, never the left-most (DEBT-5).
- **Deadline nesting:** architecture §5.1 proposes PG `statement_timeout` 5 s + pool acquire 2 s behind this 5 s gateway timeout, which breaks §5.1's own nesting rule (DEBT-6, fix at P2.C1).
- **Measurement rule:** on this WSL2 host, curl 8.5.0's `time_total` under-reports by 0.2–0.4 s (4.62–4.80 s against 5.00 s server-side, where server-side is a lower bound for any client). Timings come from server-side durations or a monotonic wrapper, never from curl (I15).
