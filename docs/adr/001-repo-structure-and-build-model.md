# ADR-001: Repo structure and build model

- **Status:** Accepted · 2026-11-27 · P1.C1
- **Resolves (MP §7):** "Don't code yet" vs incremental build · Independent deployability vs one Maven reactor · "Avoid shared code" vs shared/common

## Context
ChatConnect is ~10 services, built solo, one checkpoint at a time, and must stay green at every checkpoint (I12). It has to show microservice properties (independent build, image and deploy) without the overhead of one repo per service.

## Options
1. **Polyrepo**, one repo per service. Strongest independence, but N× CI/config upkeep and cross-repo contract changes for a single developer.
2. **Monorepo, one Maven reactor, inherited parent with shared dependencies.** Simple, but every service gets every library, and a parent change silently rebuilds and reshapes everything.
3. **Monorepo, BOM-only parent, per-service everything else** (chosen).

## Decision
- **Code from P1.** Design happens only as per-checkpoint deltas; no up-front big design phase.
- **Root `pom.xml` is BOM-only.** It manages versions (Spring Boot parent 3.5.16 + imported Spring Cloud 2025.0.3 BOM), plugin config and build rules (enforcer: Java `[17,18)`, Maven `≥3.9`; failsafe for `*IT`). It declares **no `<dependencies>`**: each service lists exactly what it uses.
- It inherits `spring-boot-starter-parent` rather than importing `spring-boot-dependencies`. That gets Boot's plugin defaults (`-parameters`, repackage, failsafe wiring, resource filtering) without copying them, and CVE overrides for DEBT-1 become one-line property overrides (`<tomcat.version>`).
- **Layout:** `services/<name>` per service; `shared/` holds **contracts only** (`contracts-events` P8, `contracts-proto` P9). Technical code (filters, config, error handlers) is duplicated per service. Rule of three: a third copy triggers a platform-starter ADR.
- **Independent deployability:** each service has its own Dockerfile, image and sha tag (P1.C4), is built with `./mvnw -pl services/<svc> -am verify`, and has its own path-filtered CI job (P1.C5). Services never depend on each other's modules.
- **Build entrypoint:** `./mvnw` (Maven 3.9.16 pinned, script-only wrapper with no committed jar, `distributionSha256Sum` pinned). Tests: `*Test` = unit (surefire), `*IT` = Testcontainers (failsafe); `./mvnw verify` runs both.

## Consequences
- **+** One clone, one command, atomic cross-service changes during a checkpoint, a single place to patch CVEs.
- **−** The shared version line couples services: bumping Boot moves all services at once. That's accepted, because I16 already pins one line, and a per-service version override is still possible if needed.
- **−** The parent POM is a blast radius: any change rebuilds all modules. CI path filters must treat `pom.xml` and `.mvn/**` as "build everything".
- **Failure mode:** someone adds `<dependencies>` to the root POM "for convenience". REVIEW checks the root has none.
- **Failure mode:** the wrapper checksum covers the `.zip`. `mvnw` falls back to `.tar.gz` when `unzip` is missing, and the check then fails by design. `unzip` is a build prerequisite (GitHub Ubuntu runners ship it).
