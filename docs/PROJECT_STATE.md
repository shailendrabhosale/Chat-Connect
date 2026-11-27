# PROJECT_STATE (the only memory, MP §5)

**Last done:** P1.C1: BOM-only parent (Boot 3.5.16 + Spring Cloud 2025.0.3), modules gateway/auth-service/chat-service (context-load smoke tests), `./mvnw` (Maven 3.9.16, sha256-pinned), enforcer Java `[17,18)`, ADR-001/002. AT `./mvnw -q verify` green in WSL (33 s, 3/3 tests) · 2026-11-27 · branch `p1-c1`
**Next:** `GO P1.C2` (actuator liveness/readiness, ECS JSON logs, Micrometer Tracing, X-Trace-Id, graceful shutdown)
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
| `unzip` in WSL | **absent**; `mvnw` then switches to `.tar.gz` and fails the pinned zip checksum. Worked around on 2026-11-27 by placing the SHA-512-verified 3.9.16 zip in `~/.m2/wrapper/dists` | **todo**: `sudo apt install -y unzip` |
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
| gateway | `services/gateway` (Spring Cloud Gateway, WebFlux) | 8080 | — | 1 | shell, no routes (P1.C3) |
| auth-service | `services/auth-service` (Spring MVC) | 8081 | — (PG at P2) | 1 | shell |
| chat-service | `services/chat-service` (Spring MVC) | 8082 | — (PG at P3) | 1 | shell |

Package root `dev.chatconnect.*`; groupId `dev.chatconnect`; version `0.1.0-SNAPSHOT`.

## Topics
none (P8).

## Schema versions
none (P2).

## Env vars
| Name | Service | Secret? | Where set |
|---|---|---|---|
| SERVER_PORT | all | no | optional override; defaults 8080/8081/8082 in `application.yml` |

## Compose profiles
none (`core` at P1.C4).

## Measurements
| Metric | Value | Command | Env | Date |
|---|---|---|---|---|

## Known debt
| ID | Item | Fix-by |
|---|---|---|
| DEBT-2 | I10 (probes, ECS logs, traceId, graceful shutdown) not in the shells yet; by roadmap design | P1.C2 |
| DEBT-1 | Spring Boot 3.5 / Spring Cloud 2025.0 have been past OSS support since 2026-06-30, so no upstream CVE patches. Mitigation: override managed dependency versions (e.g. Tomcat, Netty, Jackson, PG driver) in the parent BOM when a fix exists; Trivy exceptions (P21) only with a reason and an expiry date | ADR only (I16); Boot 4 stays in V6 |

## Open ADRs
- Accepted: ADR-001 (repo structure/build model), ADR-002 (stack pins, tool choices, D1, D2).
- Proposed, not written: ADR-022, CLAUDE.md split (D3, agent playbook §5). Your call; it changes the frozen MP.
- Pending your decision: a committed `.claude/settings.json` allowlist (`./mvnw`, `docker compose`, read-only git; playbook §4). The agent was blocked from granting its own permissions, which is correct, so add it yourself if you want it.
