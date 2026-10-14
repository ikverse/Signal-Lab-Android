# Engine overhaul: running log

What changed and why, in the order it happened. One entry per commit on `feature/engine-overhaul`. Phases follow the mission: 1 comprehension, 2 engine audit and fixes, 3 pattern research and validation, 4 Pattern Lab and UX, 5 Claude integration.

## Phase 1: comprehension (read-only)

### 2026-10-14: `docs/engine-audit.md`

- Added the architecture and engine audit. No app code changed.
- Branch `feature/engine-overhaul` created from `main` at `acb96f3` (Release 1.7.0).
- Engine tests run on this machine: 153 passed, 0 failed.
- Found while reading, carried into Phase 2 as candidates (not yet fixed): sticky scan problem line (`ScanHealth.lastProblem`), fast charts scanned by the hourly alarm when following is off, stale lab-budget defaults, unused `forwardOnly` flag, full-window recomputation every pass, live default cost of 0.20 % versus the 0.25 %/0.40 % stated in `CLAUDE.md`, no out-of-sample evaluation anywhere.
