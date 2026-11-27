# PROJECT_STATE (the only memory, MP §5)

**Last done:** P0 foundations (design only, no code): `docs/architecture.md`, `docs/agent-playbook.md`, `.gitattributes`. D1 + D2 decided; P0 environment checks pass in WSL · 2026-11-27
**Next:** decide D4 (where builds run) → `GO P1.C1`
**Version / tag:** pre-V1 · none

## Stack pins (I16; ADR-002 records them at P1.C1)
| Item | Pin | Note |
|---|---|---|
| Java | 17 | local: Ubuntu OpenJDK 17.0.21 (WSL) / Temurin 17.0.16 (Windows); images: `eclipse-temurin:17` |
| Spring Boot | 3.5.16 | last OSS 3.5.x release; future work stays on 3.x |
| Spring Cloud | 2025.0.3 | final OSS 2025.0.x train, built on Boot 3.5.15; check it accepts 3.5.16 at P1.C1 |

## P0 environment (verified 2026-11-27 from this machine)
| Item | State | Status |
|---|---|---|
| Host | Windows 11, 15.8 GB RAM, 16 logical CPUs; C: 16.6 GB free, D: 77.1 GB free | ok |
| WSL | 2.6.3, Ubuntu 24.04.3 (default distro), systemd on; `.wslconfig` memory=10GB, swap=4GB → 9,944 MiB RAM + 4,096 MiB swap | done |
| JDKs in WSL | Ubuntu OpenJDK 8, 17.0.21, 21.0.13; `java`/`javac` set to **17** | done |
| JDKs on Windows | Temurin 17.0.16, 21.0.8; Windows `JAVA_HOME` = **21** | set 17 for this project if D4 = Windows-native |
| Docker | Docker Desktop engine 29.9.3; WSL integration for Ubuntu on (`/var/run/docker.sock` present); its disk image is no longer under `%LOCALAPPDATA%\Docker\wsl\disk` (moved; new location not checked) | done |
| Ubuntu disk | still on C: (`%LOCALAPPDATA%\wsl\{03fdfa68…}`) | optional: `wsl --shutdown`, then `wsl --manage Ubuntu --move D:\WSL\Ubuntu` |
| Maven | system 3.8.7 in WSL | ok (the project uses `./mvnw` from P1.C1) |
| Node | absent | needed by P5 |
| Working folder | **`D:\SHAILU\Projects\chatconnect`** (yours), branch `main` | ok |
| WSL duplicate | `~/code/chatconnect`, copied 2026-11-27, no commits, LF-normalized | keep or delete per D4 |
| Shell | `~/.bashrc` line 119 `cd /mnt/d/shailu/prog_practices/java/` | note: new WSL terminals start on /mnt/d |

**P0 AT (in WSL), 2026-11-27:** `java`/`javac` 17.0.21 ✓ · `docker info` → 29.9.3 Docker Desktop ✓ · 9,944 MiB RAM ✓ · branch `main` ✓

## Open decisions
- ~~D1: Spring Boot line~~ **Decided 2026-11-27: Boot 3.5.16, stay on 3.x** (your call: stability). The risk is tracked as DEBT-1.
- ~~D2: Docker runtime~~ **Decided 2026-11-27: Docker Desktop's engine** (already installed). It stands in for MP §0's "Docker Engine inside WSL2". Trade-offs: Desktop's Windows-side processes use some RAM, and the prod VM (P6) will run plain Docker Engine, so compose files must not rely on Desktop-only features such as `host.docker.internal`.
- **D3: CLAUDE.md size.** It's 35 KB / 5,579 words, loaded every turn. Splitting it needs an ADR; see the agent playbook.
- **D4: where builds run.** The working folder is on D:, while MP §0 assumes a WSL repo. Keep the toolchain on the same side as the files:
  - **A. Windows-native on D:**: Temurin 17 (Windows), Docker Desktop, IntelliJ on Windows, Git Bash for the MP's bash commands, and this desktop Claude Code session as is. Native NTFS IO. Costs: deviates from MP §0 (needs an ADR); `mvnw` needs `git update-index --chmod=+x` because Windows git has `filemode=false`.
  - **B. WSL**: make `~/code/chatconnect` the working folder, with the MP's environment exactly as written. Costs: you switch to the WSL copy, and Claude Code runs as the CLI inside WSL.
  - **Avoid:** WSL tools building on `/mnt/d`, or Windows tools on `\\wsl.localhost`. Cross-boundary file IO is the slow path the MP warns about.

## Services
none yet. Planned at P1: gateway 8080, auth 8081, chat 8082.

## Topics
none (P8).

## Schema versions
none (P2).

## Env vars
| Name | Service | Secret? | Where set |
|---|---|---|---|

## Compose profiles
none (`core` at P1.C4).

## Measurements
| Metric | Value | Command | Env | Date |
|---|---|---|---|---|

## Known debt
| ID | Item | Fix-by |
|---|---|---|
| DEBT-1 | Spring Boot 3.5 / Spring Cloud 2025.0 have been past OSS support since 2026-06-30, so no upstream CVE patches. Mitigation: override managed dependency versions (e.g. Tomcat, Netty, Jackson, PG driver) in the parent BOM when a fix exists; Trivy exceptions (P21) only with a reason and an expiry date | ADR only (I16); Boot 4 stays in V6 |

## Open ADRs
ADR-001 and ADR-002 are due at P1.C1 (ADR-002 records the pins above, D2 and D4).
