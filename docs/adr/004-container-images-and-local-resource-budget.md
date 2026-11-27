# ADR-004: Container images and the local resource budget

- **Status:** Accepted · 2026-11-27 · P1.C4
- **Resolves (MP §7):** Full stack vs 16 GB RAM → profiles, memory limits, core + ≤ 2 heavy profiles, the service under work runs from the IDE. **Closes DEBT-4.**

## Context
The laptop has 16 GB of RAM. WSL is capped at 10 GB, and Docker Desktop sees 9.7 GiB. By V4 the stack has ~10 Spring services plus Kafka, Mongo, Elasticsearch and an observability stack, which can't all run at once. Every service drains for up to 20 s per shutdown phase (ADR-003), which only helps if the container actually delivers SIGTERM to the JVM and waits long enough.

## Options
- **Image build:**
  - (1) copy a host-built jar: fast, but the image can silently ship a stale jar and isn't self-contained;
  - (2) **multi-stage build from source with the pinned `./mvnw`** (chosen);
  - (3) Buildpacks (`spring-boot:build-image`): no Dockerfile, but a ~1 GB builder and less control over the user, flags and layout.
- **Base image:**
  - **Temurin JRE, Ubuntu noble** (chosen; matches the I16 pin);
  - distroless: no shell, so no curl healthcheck and no `docker exec` debugging;
  - Alpine: musl;
  - jlink custom runtime: smallest, worth it once image size or CVE count matters (P21).
- **Healthcheck:**
  - **curl**, which is already in the JRE image (verified);
  - a Java probe: one JVM start per check per service;
  - bash `/dev/tcp`: fragile.

## Decision

### Images (`services/<svc>/Dockerfile`; identical apart from module name and port, ADR-001)
- The build context is the repo root, behind a deny-by-default `.dockerignore`: only the POMs, the wrapper and `services/` get in, minus `target/`, `src/test/` and key/env files (I11).
- **Build stage:** `eclipse-temurin:17-jdk-noble` plus `unzip`. The JDK image has none, and `./mvnw` then falls back to the `.tar.gz` and fails the pinned zip checksum (the P1.C1 lesson, verified). It runs `./mvnw -pl services/<svc> -am -Dmaven.test.skip=true package` with one shared BuildKit cache, `chatconnect-m2`, mounted `sharing=locked` because the Maven local repo isn't safe for parallel writers. Tests belong to `./mvnw verify` (I12), not to image builds.
- **Layers:** `java -Djarmode=tools … extract --layers --launcher` (`layertools` is the pre-3.3 tool). Measured sizes: `dependencies` 24.7 MB → `spring-boot-loader` 696 kB → `snapshot-dependencies` → `application` 102 kB, so a code change ships ~100 kB.
- **Runtime:** `eclipse-temurin:17-jre-noble` (Temurin 17.0.21.1, Ubuntu 24.04.5 on 2026-11-27) as uid/gid **10001**. App files are root-owned, so the process can't modify its own code.
- **ENTRYPOINT is exec form, so the JVM is PID 1.** Measured with `docker stop -t 5`:

| Shape | PID 1 | Stop | Exit | Drained |
|---|---|---|---|---|
| exec form | `java` | 0.31 s | 143 | yes |
| `sh -c "java …"` | `sh` (dash doesn't exec it) | 5.30 s | **137 (SIGKILL)** | **no** |

- **JVM flags live in the ENTRYPOINT.** `JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS` would print a non-JSON "Picked up …" line, breaking ADR-003. The flags: `-XX:MaxRAMPercentage=75` (MP §3) and `-XX:+ExitOnOutOfMemoryError`, so a broken JVM crashes and the restart policy recovers it. Ergonomics choose SerialGC (under 2 GB is not "server class"; verified).
- **Gateway only:**
  - `-XX:MaxDirectMemorySize=64m`: Netty's direct buffers otherwise default to the heap size, and heap plus direct could exceed the limit, meaning a kernel SIGKILL with no drain.
  - `-Dio.netty.transport.noNative=true`: `/tmp` is a noexec tmpfs, so Netty's epoll `.so` can't load. Netty would otherwise **silently** fall back to NIO (measured: epoll mapped 0 with noexec, 3 with exec, and no log line either way). NIO is chosen explicitly; epoll vs NIO gets measured at P6.C3.
- **Labels:** `org.opencontainers.image.title`, `version` (`APP_VERSION`, which overrides the base image's inherited `24.04`) and `revision` (`GIT_SHA`).
- **Base tags, not digests:** rebuilds pick up OS and JRE patches (the DEBT-1 posture). Digest pinning and Trivy come at P21/P23. Digests on 2026-11-27: jdk `sha256:bdd7eeee…0f772`, jre `sha256:ad806840…5367c6`.

### Compose (`deploy/compose/compose.yaml`, project `chatconnect`)
- **Profiles:** `core` (P1: gateway, auth-service, chat-service); `events` (P8), `data` (P13/P14) and `obs` (P18–P20) later. Run core plus at most two heavy profiles at once.
- **Every Spring service** gets:
  - `mem_limit` and `memswap_limit` 512m (no swap, so an overrun fails loudly);
  - `cpus: 2.0` and `restart: unless-stopped`;
  - **`stop_grace_period: 30s`**, which covers the 20 s drain plus margin (DEBT-4);
  - `read_only: true` and a `/tmp` tmpfs mounted `noexec,nosuid,size=64m`;
  - `cap_drop: [ALL]` and `no-new-privileges`;
  - json-file logs, 10 MB × 3.
- **Healthcheck = readiness:** `curl -fsS …/actuator/health/readiness`, every 10 s, 3 s timeout, 3 retries, `start_period` 60 s, `start_interval` 2 s. `docker compose up --wait` gates on it. Docker never restarts a container for being unhealthy, so readiness doesn't double as liveness.
- **Ports are published on `127.0.0.1` only**, because Docker-published ports bypass host firewalls (ufw). Docker Desktop binds published ports on **Windows**, so a port free in WSL can still be taken (18080 was).
- **No `depends_on` from the gateway to services.** The gateway already tolerates missing upstreams (503, ADR-023), and a hard dependency would restart a service you stopped to run it from the IDE. Hard dependencies (a service on its DB, P2+) do use `depends_on: condition: service_healthy`.
- **IDE workflow:**
  1. `docker compose stop <svc>` **first**, because on Desktop a published port also occupies that port inside WSL.
  2. Run the service in IntelliJ.
  3. Recreate the gateway with `<SVC>_SERVICE_URI=http://$(hostname -I | awk '{print $1}'):<port>`.

  `host.docker.internal` / `host-gateway` don't work here. Measured from a container, with the JVM running in WSL:

| Target | Reaches the WSL JVM |
|---|---|
| `host.docker.internal` = `host-gateway` (192.168.65.254, IPv4 and IPv6) | no: that's Desktop's Windows-side host |
| compose network gateway (172.19.0.1) | no |
| WSL eth0 IP | **yes** (request logged with its traceId) |

  The WSL IP approach also works on plain Docker Engine (D2: nothing Desktop-only).

### Budget
| Phase | Containers | Limits | Measured idle (2026-11-27) |
|---|---|---|---|
| P1 core | gateway, auth-service, chat-service | 3 × 512 MiB = 1.5 GiB | gateway 176–190 MiB, auth 123–150 MiB, chat 128–130 MiB (≈ 0.45 GiB) |

## Consequences
- **+** Deploys drain instead of being killed, and the check is automated (`deploy/compose/smoke-core.sh` asserts exit 143, not 137). Containers are non-root and immutable, logs stay JSON-only, and the AT is one command.
- **−** Images are 444–472 MB each, but the ~396 MB JRE base is one shared layer, so the per-service cost is ~25 MB of dependencies plus ~100 kB of application. jlink is the lever if size or CVE count matters (P21).
- **−** `COPY services/` means a change in any service invalidates every image's build stage. The Maven cache keeps a rebuild to ~10–20 s, and CI path filters build only the changed services (P1.C5).
- **−** 75% heap leaves ~128 MiB for everything else. It's idle-proven only; validate under k6 at P6.C3 and watch for exit 137 / `OOMKilled` (DEBT-8).
- **−** `restart: unless-stopped` brings containers back whenever Docker Desktop restarts, unless they were stopped explicitly.
- **−** The WSL IP changes when WSL restarts, which is why the IDE command computes it each time.
