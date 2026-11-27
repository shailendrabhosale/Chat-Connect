# ADR-002: Stack pins and either/or tool choices

- **Status:** Accepted · 2026-11-27 · P1.C1
- **Resolves (MP §7):** Either/or stack items · Mandated Boot 3 vs support window · PROJECT_STATE D1, D2

## Context
I16 mandates Spring Boot 3.x on Java 17. Several MP stack items were "X or Y" and need one pick each. The local Docker runtime (D2) also differs from MP §0's wording.

## Pins (verified 2026-11-27)
| Item | Pin | Evidence |
|---|---|---|
| Java | 17 (enforcer `[17,18)`) | local Ubuntu OpenJDK 17.0.21; images `eclipse-temurin:17` (P1.C4) |
| Spring Boot | **3.5.16** | Maven Central: latest 3.5.x; endoflife.date: final 3.5 OSS release, 2026-06-25 |
| Spring Cloud | **2025.0.3** | Maven Central: latest 2025.0.x (gateway 4.3.5, commons 4.3.3); context starts on Boot 3.5.16 with no compatibility-verifier failure (P1.C1 tests) |
| Gateway starter | `spring-cloud-starter-gateway-server-webflux` | the legacy `spring-cloud-starter-gateway` 4.3.5 pulls `spring-boot-properties-migrator` (deprecated path); properties live under `spring.cloud.gateway.server.webflux.*` |
| Maven | 3.9.16 via `./mvnw` (sha256-pinned) | Maven Central: latest 3.9.x. 3.10.2 also exists and was not adopted: nothing needs it, and a bump is a one-line wrapper change plus a new checksum |

## Support window (the risk)
endoflife.date (queried 2026-11-27): Boot 3.5 and Spring Framework 6.2 **OSS support ended 2026-06-30**; commercial support runs to 2032-06-30. Boot 4.0 OSS ends 2026-12-31 and 4.1 OSS 2027-07-31.

## Options
1. **Stay on 3.5.16** (chosen, D1): stable, well-documented, matches I16. There are no further OSS patches.
2. Boot 4.x: supported, but violates I16 and adds migration churn (Jackson 3, modularised starters) to every checkpoint.

## Decision
- Stay on Boot 3.5.16 / Spring Cloud 2025.0.3. Boot 4 + Java 21 stays in the V6 backlog, and moving to it needs its own ADR.
- **DEBT-1 mitigation:** when a CVE fix exists upstream for a Boot-managed library (Tomcat, Netty, Jackson, PG driver…), override its version property in the root POM with a comment naming the CVE. Trivy exceptions (P21) need a reason and an expiry date.
- **Either/or picks:** GitHub Actions (no Jenkins server, RAM or ops) · Jaeger via OTLP (OTel-native, richer UI than Zipkin) · Zustand + React Query + Axios (less boilerplate than Redux; server state lives in React Query).
- **Docker runtime (D2):** the Docker Desktop engine with WSL integration stands in for MP §0's "Docker Engine inside WSL2". Compose files must not rely on Desktop-only features (`host.docker.internal`, Desktop extensions) because the P6 VM runs plain Docker Engine.

## Consequences
- **+** Zero version-drift work for V1–V5; agent and docs knowledge match the stack.
- **−** Unpatched transitive CVEs accumulate. Trivy (P21) will flag them, and each needs an override or a time-boxed exception. That's a real interview talking point, not a hidden one.
- **−** Desktop vs Engine parity risk; the P6 smoke on the VM is the check.
- **Revisit trigger:** a critical CVE in Spring Framework/Boot itself with no OSS fix → ADR for Boot 4 (displaces V6 position).
