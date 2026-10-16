# Engine overhaul: running log

What changed and why, in the order it happened. One entry per commit on `feature/engine-overhaul`. Phases follow the mission: 1 comprehension, 2 engine audit and fixes, 3 pattern research and validation, 4 Pattern Lab and UX, 5 Claude integration.

## Phase 1: comprehension (read-only)

### 2026-10-14: `docs/engine-audit.md`

- Added the architecture and engine audit. No app code changed.
- Branch `feature/engine-overhaul` created from `main` at `acb96f3` (Release 1.7.0).
- Engine tests run on this machine: 153 passed, 0 failed.
- Found while reading, carried into Phase 2 as candidates (not yet fixed): sticky scan problem line (`ScanHealth.lastProblem`), fast charts scanned by the hourly alarm when following is off, stale lab-budget defaults, unused `forwardOnly` flag, full-window recomputation every pass, live default cost of 0.20 % versus the 0.25 %/0.40 % stated in `CLAUDE.md`, no out-of-sample evaluation anywhere.

## Signal Lab 2 rebuild (branch `v2`)

The five-phase overhaul was replaced on 2026-10-16 by a from-scratch rebuild; plan in `docs/v2-plan.md`, evidence in
`docs/strategy-research.md`. From M2 on, work runs under a standing approval (see `CLAUDE.md`); every decision made without
asking is logged here.

### M0: toolchain and research (a4b80ee)

- Room 2.8.5 + KSP 2.3.12, WorkManager 2.12.0, Navigation Compose 2.10.2, kotlinx.serialization 1.11.0, Material 3 adaptive
  (from the Compose BOM). KSP runs under AGP 9's built-in Kotlin; no fallback needed.
- Engine test helpers and golden data moved to the `testFixtures` source set.

### M1: engine groundwork

- Deleting the five Signal Lab 1 engine files here would have broken the 1.7 app code still on the branch, so they stay until
  M6 and carry a header saying so; `LayeringTest` keeps new `strategy/` and `eval/` code off them.
- Golden paper-trade, fingerprint and exit tests read the research's saved signals (`Golden.researchSignals`) or synthetic
  signals instead of `Signals.kt`.
- `RandomFingerprintTest`: the saved signals arrive in name order, which moved the last digit of one sum
  (18.34023686426625 → 18.340236864266156); its three sums are now compared to 1e-12 relative, counts still exact.
