# Agent playbook: model, effort, sessions

Written 2026-11-27, before P1. Re-check prices and plan limits whenever this file is used to make a cost call.

## 1. Where the agent runs
- **Run the agent on the same side as the files and toolchain (PROJECT_STATE D4).** Windows-native on `D:\SHAILU\Projects\chatconnect`: this desktop app session (PowerShell/Git Bash) works directly. WSL: the `claude` CLI in a WSL terminal in the WSL working folder. The mixed setups are the slow ones: WSL tools on `/mnt/d`, or Windows tools on `\\wsl.localhost` paths.
- If builds run on Windows, mark `mvnw` executable in git at P1.C1 (`git update-index --chmod=+x mvnw`). Windows git runs with `filemode=false`, and the Linux CI runner needs the bit.
- If D4 = WSL, the parent `D:\SHAILU\CLAUDE.md` guidelines stop loading. If you want them, copy them into `~/.claude/CLAUDE.md` inside WSL. They conflict in one place: the parent says "present interpretations, don't pick silently", while MP §5 says "decide, log ADR, move on" for everything that isn't IRREVERSIBLE. Inside this project the MP is the more specific rule, so it wins.

## 2. Model × effort by checkpoint class
Relative Anthropic API list prices per MTok (in/out; skill cache dated 2026-11-03): Haiku 5.5 $0.10/$0.50 · Sonnet 5.5 $2/$10 · Opus 5.5 $4/$20 · Fable 5.2 $8/$40. **Subscription usage limits were not checked.** Look at your plan's usage page before committing to an Opus-heavy schedule.

| Class | Checkpoints | Model @ effort | Why |
|---|---|---|---|
| A. Irreversible / concurrency-critical | P3.C1, P3.C3, P3.C5, P4.C2, P8.C1, P8.C2, P10.C3–C4, P13.C3–C5, P21.C2, P22.C2 | **Opus 5.5 @ high**; xhigh for P8.C2 and P13.C5 | A wrong schema, protocol or relay means a rewrite or silent loss, so deep reasoning pays for itself |
| B. Feature build | the rest of P2–P5, P7, P9, P11, P14–P16, P25–P27 | **Sonnet 5.5 @ high** | The ATs specify the work well; Sonnet is the everyday coding tier at half Opus's price |
| C. Scaffold / infra / config | P1.C2–C5, P6.C1–C2, P18, P19, P22.C1, P23 | **Sonnet 5.5 @ medium** | Boilerplate plus verify loops; effort above medium buys little |
| D. Review | `REVIEW P<n>` | **Opus 5.5 @ high** + `/code-review high` on the phase diff | Auditing against invariants is judgment-heavy, and it's one run per phase |
| E. Text | STATE, DRILL/ANS, BULLETS, ADR prose | **Sonnet 5.5 @ low–medium** | Accuracy comes from reading the files, not from deep reasoning |
| F. FIX | any | Sonnet 5.5 @ high → **escalate to Opus 5.5 @ high after 2 failed hypotheses** | Most fixes are config; races (Testcontainers, Kafka rebalance, WS ordering) need Opus |
| Fable 5.2 | none by default | Only for a heisenbug Opus can't crack in one session | 2× Opus price |

**P1 specifically:** P1.C1 on Opus 5.5 @ high, because ADR-002 (the Boot line) and the BOM coordinates cascade into everything. P1.C2–C5 on Sonnet 5.5 @ medium. REVIEW P1 on Opus 5.5 @ high.

## 3. Sessions and subagents
- **One checkpoint per fresh session** (`/clear` between checkpoints). PROJECT_STATE is the handoff; long sessions keep re-reading old tool output and burn quota.
- V1 needs no subagents because the repo is small. From V2: Explore on a cheap model for cross-module sweeps, Plan for option analysis at IRREVERSIBLE gates, and `/code-review` at REVIEW.
- Run sessions in parallel only for independent modules in separate worktrees. Never put two writers in one Maven module.
- Use one branch per checkpoint (`p1-c1`) with a PR to `main`. P1.C5's branch protection needs PRs anyway.

## 4. Guardrails
- **Version drift is the main agent failure mode.** Before using an API that's new in the chosen Boot/Cloud train, read its reference docs or release notes. Trust the compile/test loop over memory. Boot 3.5 is well covered in model training data, but Spring Cloud 2025.0 renamed some gateway starters and properties, so check those against the 2025.0 reference docs.
- At P1.C1, allowlist `./mvnw`, `docker compose` and read-only git in a committed `.claude/settings.json`, so routine runs stop prompting.
- The agent never writes `.env` files or keys into the repo (I11). gitleaks enforces this from P1.C5.

## 5. Context cost of the MP
`CLAUDE.md` is 35 KB and 5,579 words (estimated 9–11k tokens, not measured with a tokenizer), and it loads on **every turn**. Most of it (the V2–V5 roadmap and §6–§9) is irrelevant to any single checkpoint.
- **Proposal (needs ADR-022, the first number after §7's reserved 001–021):** keep §0, §1, §4 and §5 plus a "current phase" pointer in `CLAUDE.md`, and move §2, §3 and §6–§10 to `docs/mp/*.md`, read on demand at `GO`.
- Trade-off: one file as the single source of truth, against per-turn cost and attention spread thin.
- Recommendation: do it at P1.C1, or after V1 if quota holds.

## 6. Human effort (estimate, not measured)
Each checkpoint is nominally 2–3 h. The 1.5× overrun factor is an assumption; replace it with your measured actuals.

| Version | Checkpoints | Nominal | With 1.5× overrun |
|---|---|---|---|
| P0 env | — | 2–4 h | — |
| V1 | 27 | 54–81 h | 81–122 h |
| V2 | 21 | 42–63 h | 63–95 h |
| V3 | 18 | 36–54 h | 54–81 h |
| V4 | 20 | 40–60 h | 60–90 h |
| V5 | 12 | 24–36 h | 36–54 h |
| **Total** | **98** | **196–294 h** | **294–441 h** |

At about 4 h/day that's 20–30 working days for V1, and roughly 3.5–5 months overall. Logging actual hours per checkpoint would let you recalibrate after P2.
