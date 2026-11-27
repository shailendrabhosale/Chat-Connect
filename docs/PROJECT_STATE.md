# PROJECT_STATE (the only memory, MP §5)

**Last done:** P1.C5: GitHub remote `shailendrabhosale/Chat-Connect` (public, D5). CI `ci.yml` = changed-module `./mvnw verify` matrix + gitleaks (PR range / main history) → one aggregate `required` check. Branch protection on `main`: `required` bound to github-actions, PR mandatory, `enforce_admins`, linear history. ADR-024. AT: PR #2 (failing test) and PR #3 (fake secret) both blocked, and a direct push is rejected (GH006) · 2026-11-27
**Next:** `REVIEW P1` (phase end: audit vs ATs, §1, §6), then tag `p1-done`. Merge flow from now on: branch → PR → green `required` → `gh pr merge <n> --squash`
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
| GitHub | remote `origin` = `https://github.com/shailendrabhosale/Chat-Connect` (public). `gh` 2.45.0 signed in with a classic token (scopes `repo`, `workflow`, `read:org`, `gist`; 30 days → **renew ~2026-12-27**). `gh auth setup-git` is the git credential helper. Device login (`--web`) fails with `slow_down` on this gh version | done |
| IntelliJ | — | open `\\wsl.localhost\Ubuntu\home\algosatan\code\chatconnect` with the WSL JDK 17 as project SDK |
| Docker Desktop ports | published ports are bound on **Windows**, so a port free in WSL can still be busy (18080 was, PID 18008). Check both sides: `ss -tln` and `netstat.exe -ano`. Containers reach a WSL-run JVM only via the WSL eth0 IP, not via `host.docker.internal` (ADR-004) | known |
| Shell | `~/.bashrc` line 119 `cd /mnt/d/shailu/prog_practices/java/` | note: new WSL terminals start on /mnt/d, so `cd ~/code/chatconnect` first |

**P0 AT (in WSL), 2026-11-27:** `java`/`javac` 17.0.21 ✓ · `docker info` → 29.9.3 Docker Desktop ✓ · 9,944 MiB RAM ✓ · branch `main` ✓

## Open decisions
- ~~D1: Spring Boot line~~ **Decided 2026-11-27: Boot 3.5.16, stay on 3.x** (your call: stability). The risk is tracked as DEBT-1.
- ~~D2: Docker runtime~~ **Decided 2026-11-27: Docker Desktop's engine** (already installed). It stands in for MP §0's "Docker Engine inside WSL2". Trade-offs: Desktop's Windows-side processes use some RAM, and the prod VM (P6) will run plain Docker Engine, so compose files must not rely on Desktop-only features such as `host.docker.internal`.
- **D3: CLAUDE.md size.** It's 35 KB / 5,579 words, loaded every turn. Splitting it needs an ADR; see the agent playbook.
- ~~D4: where builds run~~ **Decided 2026-11-27: WSL, working folder `~/code/chatconnect`**, which matches MP §0. Files, JDK, Maven, git, Docker socket and the Claude Code CLI all stay on the WSL side. Avoid building from `/mnt/d` or editing through `\\wsl.localhost` with Windows tools, since cross-boundary file IO is slow. IntelliJ's WSL support is the exception.
- ~~D5: cost and repo visibility~~ **Decided 2026-11-27: zero cost.** The GitHub repo is **public**: free hosted Actions runners and free branch protection (a private repo on GitHub Free spends paid minutes, $12/month included). Free tiers only from here on, including the P6 VM. Before the first push, gitleaks v8.30.1 scanned all history (8 commits) and the working tree: no leaks. Published with the repo: commit email `shailu.codes@gmail.com` and local paths in docs.

## Services
| Name | Module | Port | DB | Replicas | State |
|---|---|---|---|---|---|
| gateway | `services/gateway` (Spring Cloud Gateway, WebFlux) | 8080 | — | 1 | routes `/api/auth/**`, `/api/chat/**`; timeouts + RFC 7807 edge errors (ADR-023); image `chatconnect/gateway` |
| auth-service | `services/auth-service` (Spring MVC) | 8081 | — (PG at P2) | 1 | `GET /api/auth/v1/ping`; image `chatconnect/auth-service` |
| chat-service | `services/chat-service` (Spring MVC) | 8082 | — (PG at P3) | 1 | `GET /api/chat/v1/ping`; image `chatconnect/chat-service` |

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
| AUTH_SERVICE_URI | gateway | no | route target; default `http://localhost:8081`; compose default `http://auth-service:8081` |
| CHAT_SERVICE_URI | gateway | no | route target; default `http://localhost:8082`; compose default `http://chat-service:8082`, overridden to the WSL IP in the IDE workflow |
| GIT_SHA, APP_VERSION | compose build args | no | OCI `revision`/`version` labels; default `dev` (the smoke script sets GIT_SHA) |
| IMAGE_TAG | compose | no | image tag; default `dev` (GHCR + sha tags at P6.C2) |

## Compose profiles
| Profile | Services | Since | Run |
|---|---|---|---|
| core | gateway, auth-service, chat-service | P1.C4 | `cd deploy/compose && docker compose --profile core up -d --build --wait`; full AT: `deploy/compose/smoke-core.sh` |

Rule (ADR-004): core + at most 2 heavy profiles at once. IDE workflow: stop the service's container first, then point the gateway at `http://$(hostname -I | awk '{print $1}'):<port>`.

## Measurements
| Metric | Value | Command | Env | Date |
|---|---|---|---|---|
| gateway hung-connect → 503 | ~1.03 s (warm), one attempt | `GatewayConnectTimeoutTest` (`@RepeatedTest(3)` probe) | WSL2, local jars | 2026-11-27 |
| gateway slow-upstream → 504 | 5.003–5.045 s gateway-side | 3 × `curl :8090/api/chat/v1/ping` against a 7 s stub; ECS `event.duration` + monotonic wrapper | WSL2, local jars | 2026-11-27 |

| core profile idle memory | gateway 176–190 MiB, auth 123–150 MiB, chat 128–130 MiB (limits 3 × 512 MiB) | `docker stats --no-stream` in `smoke-core.sh` | Docker Desktop 29.9.3, WSL2, 2 runs | 2026-11-27 |
| core profile up → all healthy | 12.6 s (images cached); 28.5 s incl. image rebuilds with a warm Maven cache | `smoke-core.sh` (`up --build --wait`) | same | 2026-11-27 |
| gateway image cold build | 54 s | `docker build -f services/gateway/Dockerfile .` (empty Maven cache) | same | 2026-11-27 |
| container SIGTERM stop | 0.3–0.5 s, exit 143 (shell-form ENTRYPOINT: 5.3 s, exit 137, no drain) | `smoke-core.sh`; `docker stop -t 5` probe | same | 2026-11-27 |

| CI first full run (PR #1) | builds 35–47 s (3 modules in parallel), gitleaks 11 s, `changes` 3 s; 72 tests, 0 skipped | GitHub Actions, ubuntu-24.04 | GitHub-hosted runner | 2026-11-27 |

**Measurement rule (I15):** on this host, curl 8.5.0's `time_total` under-reports by 0.2–0.4 s (4.62–4.80 s against 5.00 s server-side). Take timings from server-side durations or a monotonic wrapper, never from curl.

## Known debt
| ID | Item | Fix-by |
|---|---|---|
| ~~DEBT-2~~ | ~~I10 not in the shells~~: closed at P1.C2 | done |
| DEBT-3 | gateway `/actuator/health` reachable on the public port (details-free); Caddy must block `/actuator/*` | P6.C2 |
| DEBT-5 | SCG appends the client's `X-Forwarded-For`; IP-keyed logic (login throttle, rate limits) must use the right-most trusted hop, never the left-most | P2.C2 |
| DEBT-6 | Deadline nesting: architecture §5.1 proposes PG `statement_timeout` 5 s + pool acquire 2 s behind a 5 s gateway timeout, which breaks §5.1's own rule. Set e.g. acquire 1 s, `statement_timeout` 3 s | P2.C1 |
| DEBT-7 | The gateway bounds only time-to-headers (5 s); a body stalling after headers is bounded by the client (browser 10 s). Decide Reactor Netty's read timeout together with the `/ws` timeout | P4.C1 |
| ~~DEBT-4~~ | ~~stop grace + exec-form ENTRYPOINT~~: closed at P1.C4 (30 s grace, exec form, exit 143 asserted by `smoke-core.sh`) | done |
| DEBT-8 | Container memory and transport are proven idle only: 75% heap + 64m direct (gateway) inside 512m, and NIO chosen over epoll (noexec `/tmp`). Validate under k6 (watch exit 137 / `OOMKilled`) and measure epoll vs NIO | P6.C3 |
| DEBT-1 | Spring Boot 3.5 / Spring Cloud 2025.0 have been past OSS support since 2026-06-30, so no upstream CVE patches. Mitigation: override managed dependency versions (e.g. Tomcat, Netty, Jackson, PG driver) in the parent BOM when a fix exists; Trivy exceptions (P21) only with a reason and an expiry date | ADR only (I16); Boot 4 stays in V6 |

## Open ADRs
- Accepted: ADR-001 (repo structure/build model), ADR-002 (stack pins, tool choices, D1, D2), ADR-003 (observability baseline, W3C trace-id correlation), ADR-004 (container images, local resource budget), ADR-024 (CI pipeline, merge gate), ADR-023 (gateway routing, timeouts, edge errors; takes 023 because §7 reserves 001–021 and 022 is earmarked for D3).
- Proposed, not written: ADR-022, CLAUDE.md split (D3, agent playbook §5). Your call; it changes the frozen MP.
- ~~Pending: `.claude/settings.json` allowlist~~ **Done 2026-11-27:** committed with `./mvnw`, the non-destructive `docker compose` subcommands (up/ps/logs/config/build/stop) and read-only git (status/diff/log/show). `docker compose down` and `git branch` stay behind prompts (`down -v` deletes volumes, `branch -D` deletes branches). Gap: `docker compose --profile … <cmd>` doesn't match these prefixes, so it still prompts.
