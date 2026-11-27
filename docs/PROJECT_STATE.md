# PROJECT_STATE (the only memory, MP §5)

**Last done:** P1.C3: gateway static routes `/api/auth/**` → auth, `/api/chat/**` → chat (paths unchanged, no retries); connect 1 s, response 5 s, pool idle 15 s; RFC 7807 edge errors (503/504/502/404/500, `Cache-Control: no-store`, no internal addresses); `GET /api/{auth,chat}/v1/ping`; ADR-023. AT: `curl :8080/api/{chat,auth}/v1/ping` → 200, with the same traceId in the gateway and service logs. `./mvnw -q verify` green (72 tests) · 2026-11-27 · branch `p1-c3`
**Next:** `GO P1.C4` (multi-stage Dockerfiles, compose `core` profile, healthchecks, mem limits, ADR-004; closes DEBT-4)
**Version / tag:** pre-V1 · none

## Stack pins (I16; recorded in ADR-002)
| Item | Pin | Note |
|---|---|---|
| Java | 17 | local: Ubuntu OpenJDK 17.0.21 (WSL); images: `eclipse-temurin:17` (same OpenJDK 17 line) |
| Spring Boot | 3.5.16 | last OSS 3.5.x release; future work stays on 3.x |
| Spring Cloud | 2025.0.3 | final OSS 2025.0.x train; verified at P1.C1: contexts start on Boot 3.5.16 |
| Gateway starter | `spring-cloud-starter-gateway-server-webflux` (4.3.5) | not the legacy `spring-cloud-starter-gateway` |
| Maven | 3.9.16 via `./mvnw` | script-only wrapper, `distributionSha256Sum` pinned to the `.zip` |

## P0 environment (verified 2026-11-27 from this machine)
| Item | State | Status |
|---|---|---|
| Host | Windows 11, 15.8 GB RAM, 16 logical CPUs; C: 16.6 GB free, D: 77.1 GB free | ok |
| WSL | 2.6.3, Ubuntu 24.04.3 (default distro), systemd on; `.wslconfig` memory=10GB, swap=4GB → 9,944 MiB RAM + 4,096 MiB swap | done |
| JDKs in WSL | Ubuntu OpenJDK 8, 17.0.21, 21.0.13; `java`/`javac` set to **17** | done |
| JDKs on Windows | Temurin 17.0.16, 21.0.8; Windows `JAVA_HOME` = 21 | not used (D4 = WSL) |
| Docker | Docker Desktop engine 29.9.3; WSL integration for Ubuntu on (`/var/run/docker.sock` present); its disk image is no longer under `%LOCALAPPDATA%\Docker\wsl\disk` (moved; new location not checked) | done |
| Ubuntu disk | still on C: (`%LOCALAPPDATA%\wsl\{03fdfa68…}`) | optional: `wsl --shutdown`, then `wsl --manage Ubuntu --move D:\WSL\Ubuntu` |
| Maven | system 3.8.7 in WSL; project uses `./mvnw` → 3.9.16 | ok |
| `unzip` in WSL | installed 2026-11-27; `./mvnw` downloads + checksum-verifies Maven 3.9.16 from a clean cache | done |
| Node | absent | needed by P5 |
| Working folder | **`~/code/chatconnect`** (WSL), branch `main`, synced to `9d132b7`; git `filemode=true`, `ignorecase=false`, `autocrlf=input`; no remote yet (GitHub at P1.C5) | done |
| Previous folder | `D:\SHAILU\Projects\chatconnect`: holds `9d132b7` only and won't receive further work | yours to archive or delete |
| Claude Code CLI in WSL | installed | done |
| IntelliJ | — | open `\\wsl.localhost\Ubuntu\home\algosatan\code\chatconnect` with the WSL JDK 17 as project SDK |
| Shell | `~/.bashrc` line 119 `cd /mnt/d/shailu/prog_practices/java/` | note: new WSL terminals start on /mnt/d, so `cd ~/code/chatconnect` first |

**P0 AT (in WSL), 2026-11-27:** `java`/`javac` 17.0.21 ✓ · `docker info` → 29.9.3 Docker Desktop ✓ · 9,944 MiB RAM ✓ · branch `main` ✓

## Open decisions
- ~~D1: Spring Boot line~~ **Decided 2026-11-27: Boot 3.5.16, stay on 3.x** (your call: stability). The risk is tracked as DEBT-1.
- ~~D2: Docker runtime~~ **Decided 2026-11-27: Docker Desktop's engine** (already installed). It stands in for MP §0's "Docker Engine inside WSL2". Trade-offs: Desktop's Windows-side processes use some RAM, and the prod VM (P6) will run plain Docker Engine, so compose files must not rely on Desktop-only features such as `host.docker.internal`.
- **D3: CLAUDE.md size.** It's 35 KB / 5,579 words, loaded every turn. Splitting it needs an ADR; see the agent playbook.
- ~~D4: where builds run~~ **Decided 2026-11-27: WSL, working folder `~/code/chatconnect`**, which matches MP §0. Files, JDK, Maven, git, Docker socket and the Claude Code CLI all stay on the WSL side. Avoid building from `/mnt/d` or editing through `\\wsl.localhost` with Windows tools, since cross-boundary file IO is slow. IntelliJ's WSL support is the exception.

## Services
| Name | Module | Port | DB | Replicas | State |
|---|---|---|---|---|---|
| gateway | `services/gateway` (Spring Cloud Gateway, WebFlux) | 8080 | — | 1 | routes `/api/auth/**`, `/api/chat/**`; timeouts + RFC 7807 edge errors (ADR-023) |
| auth-service | `services/auth-service` (Spring MVC) | 8081 | — (PG at P2) | 1 | `GET /api/auth/v1/ping` |
| chat-service | `services/chat-service` (Spring MVC) | 8082 | — (PG at P3) | 1 | `GET /api/chat/v1/ping` |

Endpoints on every service: `/actuator/health/liveness`, `/actuator/health/readiness` (only `health` exposed). Every response carries `X-Trace-Id`.
Package root `dev.chatconnect.*`; groupId `dev.chatconnect`; version `0.1.0-SNAPSHOT`.

## Topics
none (P8).

## Schema versions
none (P2).

## Env vars
| Name | Service | Secret? | Where set |
|---|---|---|---|
| SERVER_PORT | all | no | optional override; defaults 8080/8081/8082 in `application.yml` |
| APP_ENV | all | no | ECS `service.environment`; default `local` |
| TRACING_SAMPLING_PROBABILITY | all | no | default `1.0`; prod 0.1 at P20 (trace IDs are logged even when unsampled) |
| HOSTNAME | all (read) | no | ECS `service.node.name`; set by Docker/K8s; default `local` |
| AUTH_SERVICE_URI | gateway | no | route target; default `http://localhost:8081`; compose sets the service DNS name (P1.C4) |
| CHAT_SERVICE_URI | gateway | no | route target; default `http://localhost:8082`; compose sets the service DNS name (P1.C4) |

## Compose profiles
none (`core` at P1.C4).

## Measurements
| Metric | Value | Command | Env | Date |
|---|---|---|---|---|
| gateway hung-connect → 503 | ~1.03 s (warm), one attempt | `GatewayConnectTimeoutTest` (`@RepeatedTest(3)` probe) | WSL2, local jars | 2026-11-27 |
| gateway slow-upstream → 504 | 5.003–5.045 s gateway-side | 3 × `curl :8090/api/chat/v1/ping` against a 7 s stub; ECS `event.duration` + monotonic wrapper | WSL2, local jars | 2026-11-27 |

**Measurement rule (I15):** on this host, curl 8.5.0's `time_total` under-reports by 0.2–0.4 s (4.62–4.80 s against 5.00 s server-side). Take timings from server-side durations or a monotonic wrapper, never from curl.

## Known debt
| ID | Item | Fix-by |
|---|---|---|
| ~~DEBT-2~~ | ~~I10 not in the shells~~: closed at P1.C2 | done |
| DEBT-3 | gateway `/actuator/health` reachable on the public port (details-free); Caddy must block `/actuator/*` | P6.C2 |
| DEBT-5 | SCG appends the client's `X-Forwarded-For`; IP-keyed logic (login throttle, rate limits) must use the right-most trusted hop, never the left-most | P2.C2 |
| DEBT-6 | Deadline nesting: architecture §5.1 proposes PG `statement_timeout` 5 s + pool acquire 2 s behind a 5 s gateway timeout, which breaks §5.1's own rule. Set e.g. acquire 1 s, `statement_timeout` 3 s | P2.C1 |
| DEBT-7 | The gateway bounds only time-to-headers (5 s); a body stalling after headers is bounded by the client (browser 10 s). Decide Reactor Netty's read timeout together with the `/ws` timeout | P4.C1 |
| DEBT-4 | compose must set `stop_grace_period` ≥ 25 s (drain is 20 s/phase; Docker default 10 s SIGKILLs) and exec-form ENTRYPOINT so the JVM gets SIGTERM | P1.C4 |
| DEBT-1 | Spring Boot 3.5 / Spring Cloud 2025.0 have been past OSS support since 2026-06-30, so no upstream CVE patches. Mitigation: override managed dependency versions (e.g. Tomcat, Netty, Jackson, PG driver) in the parent BOM when a fix exists; Trivy exceptions (P21) only with a reason and an expiry date | ADR only (I16); Boot 4 stays in V6 |

## Open ADRs
- Accepted: ADR-001 (repo structure/build model), ADR-002 (stack pins, tool choices, D1, D2), ADR-003 (observability baseline, W3C trace-id correlation), ADR-023 (gateway routing, timeouts, edge errors; takes 023 because §7 reserves 001–021 and 022 is earmarked for D3).
- Proposed, not written: ADR-022, CLAUDE.md split (D3, agent playbook §5). Your call; it changes the frozen MP.
- Pending your decision: a committed `.claude/settings.json` allowlist (`./mvnw`, `docker compose`, read-only git; playbook §4). The agent was blocked from granting its own permissions, which is correct. Steps were given in the P1.C2 report: approve a one-off write in default mode, or add the rules via `/permissions` → Project settings.
