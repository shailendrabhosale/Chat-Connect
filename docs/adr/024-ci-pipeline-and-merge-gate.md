# ADR-024: CI pipeline and merge gate

- **Status:** Accepted · 2026-11-27 · P1.C5
- **Numbering:** MP §7 assigns no ADR to P1.C5, 022 is earmarked for D3 and 023 is taken, so this is 024.
- **Implements:** I11 (gitleaks from P1), I12 (green at every checkpoint), ADR-001's path-filtered per-module CI, and D5 (public repo, zero cost).

## Context
The repo is public (D5), so GitHub-hosted runners and branch protection are free. CI has to build only the modules a change touches (ADR-001), scan every change for secrets, and **enforce** the result, including for the owner.

## Options
- **Workflow-level `on.paths` filters:** a skipped workflow never reports, so a required check stays *pending* forever and PRs become unmergeable. Rejected.
- **A third-party path-filter action:** a supply-chain dependency for 25 lines of bash. Rejected.
- **gitleaks-action** (a JS action; licence key for organisations) vs **the official gitleaks image pinned by digest:** the image was chosen.
- **The aggregate-check pattern** (chosen): every job feeds one `required` job, and protection requires only that one.

## Decision
**Pipeline** (`.github/workflows/ci.yml`, triggered by PRs to `main` and pushes to `main`): `changes` → `build (<module>)` matrix → `gitleaks` → `required`. `required` runs `if: always()` and fails unless every job succeeded or was skipped.

`.github/scripts/changed-modules.sh` decides what builds:

| Change | Builds |
|---|---|
| root `pom.xml`, `mvnw`, `.mvn/`, `.github/` | every module (the ADR-001 blast radius) |
| `services/<m>/**` (incl. its Dockerfile) | `<m>` via `./mvnw -pl <m> -am verify` |
| docs, compose, ADRs, `.claude/` | nothing; gitleaks still runs |
| first push of a branch (no or zero base) | every module |

New modules are picked up from `services/*/pom.xml`, and deleted ones are ignored. All rules are tested on real history plus synthetic commits.

**Hardening**
- Actions are pinned to commit SHAs (`checkout` v7.0.1 `3d3c42e`, `setup-java` v6.3.0 `d1374f8`), and the gitleaks image to digest `sha256:c00b6bd…` (v8.30.1).
- The token has `permissions: contents: read`, and checkout runs with `persist-credentials: false`.
- Inputs reach scripts only through `env:`, never interpolated into `run:` (no expression injection).
- Every job has a timeout, and superseded PR runs are cancelled.
- Dependabot updates the pinned actions weekly. It doesn't touch Maven, because I16 pins the Boot line and CVE overrides are manual (DEBT-1).

**gitleaks scope:** a PR scans `base..head`, only its own commits; a push to `main` scans main's full history (`HEAD`). gitleaks' default is **all refs**, and `fetch-depth: 0` fetches every branch, so the fake key on one PR's branch also failed an unrelated PR (#2). Fixed in #4, which scanned exactly 1 commit.

**Branch protection on `main`**
- Required check `required`, **bound to app 15368 (github-actions)**, so a status with the same name from another app or token can't satisfy it.
- `strict`: the branch must be up to date with `main`.
- A PR is required, with 0 approvals (solo; REVIEW and `/code-review` stand in for a reviewer).
- **`enforce_admins`**, linear history, and no force-push or deletion.

**Merge flow:** branch per checkpoint → PR → green `required` → `gh pr merge <n> --squash`. Local `git merge --ff-only` followed by `git push origin main` is rejected now.

## Evidence (2026-11-27, `shailendrabhosale/Chat-Connect`)
| Check | Result |
|---|---|
| First run (PR #1, all modules) | 72 tests: gateway 38, auth 17, chat 17, **0 skipped** (`GatewayConnectTimeoutTest` ran on the runner). Builds 35–47 s in parallel, gitleaks 11 s, `changes` 3 s |
| Owner pushes an unchecked commit to `main` | rejected: `GH006 … Changes must be made through a pull request … Required status check "required" is expected` |
| PR #2, deliberately failing test | only chat-service built; `PingControllerTest.pingReturnsServiceIdentity:30` failed; `required` failed; `gh pr merge` refused ("the base branch policy prohibits the merge") |
| PR #3, fake `api_key` in `docs/at/` | build skipped (docs only); gitleaks found 1 leak in 1 commit; `required` failed; merge refused |
| PR #4, scope fix | gitleaks scanned 1 commit, clean, merged by squash |

## Consequences
- **+** Zero cost while the repo is public, and the gate holds even for the owner. A docs-only PR runs in ~30 s.
- **−** A direct push of a commit that is the head of a PR **already satisfying every rule** is accepted and recorded as merging that PR. That's how #1 merged early. It went through the gate, but it's surprising; always merge with `gh pr merge`.
- **−** PR scans see only the PR's commits. Anything that predates CI is covered by the push-to-`main` full scan, and the pre-publish full-history scan was clean (D5).
- **−** The fake key stays visible in closed PR #3's ref; GitHub keeps `refs/pull/N` forever. It's random bytes, valid nowhere.
- **−** 0 approvals means no second reviewer. If the repo ever goes private: Actions spends paid minutes ($12/month included ≈ 2,000 Linux minutes), and enforced protection has to be re-checked.
