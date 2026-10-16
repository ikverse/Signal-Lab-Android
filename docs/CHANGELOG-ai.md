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

### M2: strategy interface, first two built-ins, fills, baseline, backtest

- New `engine.strategy` (`Strategy`, `Tier`, `ExitPlan`, `Breakout`, `TrendTimer`, `Builtins`) and `engine.eval` (`Fills`,
  `Baseline`, `Backtest`). They use only the kept core (candles, indicators, regime, RNG, cluster statistics); the old
  `ExitRule`/`ExitPolicy` are not used because they depend on the old lab language.
- Decisions made without asking:
  - A strategy's own exit signal, seen at a candle's close, fills at the **next candle's open** (the first price anyone could
    act on), matching entry at the next open. Time exits still fill at the close of the last candle; the stop is checked first.
  - Breakout enters on the first close of a run above the prior 50-day high (not every day it stays above). Exit plan: close
    below the prior 20-day low, 60 days at most, safety stop 3 ATR.
  - Trend timer has no price stop (its edge is drawdown avoidance); a 200-day cap keeps every window finite for the baseline.
  - Weeks for clustering start Monday 00:00 UTC (v1 used `time / 7 days`, which starts on Thursdays).
  - v2 baseline seeds are prefixed with "v2" so they never coincide with v1 draws.
  - Only the engine changed, so the app suite was not rerun for this milestone; it runs again at M3.
- First backtest on the 12 golden coins, daily, 2019-01 to 2026-09, research costs:

  | strategy | calls | up | net per call | excess vs random | cluster t | weeks |
  |---|---|---|---|---|---|---|
  | breakout50_1d | 274 | 39% | +19.50% | −3.05% | −0.66 | 132 |
  | trend50_1d | 912 | 22% | +12.95% | +5.58% | 1.34 | 254 |

  Neither beats random entries with confidence here: random entries with the same wide exits also made money in a mostly
  rising market. Launch tiers stay as the research suggests; the evidence columns (M7) will move them.

### M3: data port

- Each candle keeps its USDT turnover (kline field 7); `candles.db` goes to version 4 with an `ALTER TABLE` that keeps every
  stored row (old rows count as volume × close). `CandleStore.quoteVolumeSince` and `firstOpens` feed the universe.
- Daily candles are never trimmed (`DataConfig.ALWAYS_KEPT`), and `DailyHistory` fetches them back to the listing
  (from 14 Jul 2017; the days before a listing are remembered as a known gap and asked once).
- `Universe.select`: the 40 busiest USDT coins by 30-day turnover that pass the coin filter and were listed at least 90 days
  ago, plus pins (which override ranking and age, never delisting), minus excludes. A coin whose age is unknown waits.
- Futures: `FuturesClient` (funding history, open-interest history, premium index) goes through the spot client's pacing and
  retries via `BinanceClient.fetchFrom`; a symbol with no perpetual is no data, not an error. `DerivativesStore`
  (`derivatives.db`) and `DerivativesSync` (funding back to Sep 2019; open interest forward from what is stored, never older
  than 29 days, so it accumulates on the phone). Nothing is asked on Binance.US.
- Decision: the liquidation veto is deferred. Binance has no key-free REST history of liquidations, and its stream reports
  at most one per symbol per second, so totals undercount in exactly the cascades that matter.
- Engine 172, app 752 tests pass.

### M4 + M5: ledger, importer, live runner, daily scan

- `ledger.db` (Room, schema in `app/schemas/`): calls, exits, the strategy registry, custom strategies and their stops,
  reports, pins, settings, Signal Lab 1 list names, and the import log. `calls`, `exits`, `strategies`, `custom_strategies`,
  `custom_stops` and `import_log` refuse UPDATE and DELETE by trigger; the triggers are created on create and on every open.
- `LegacyImporter` reads `signal_lab.db` through `ledger/legacy/SignalLabV6` (a verbatim copy of Signal Lab 1's six schema
  steps, so it outlives Signal Lab 1's code) in one transaction, keyed so repeats add nothing. Decisions:
  - Signal Lab 1 keeps writing to its file until M6, so the importer runs at every start and adds only what is new; a trade's
    "unresolved" state is the absence of an exit row (a status written once could not change), and only the first import
    keeps a copy of the file, so copies never pile up and nothing ever needs deleting.
  - Pins come from the coins of *active* lists only; alerts are not imported (counted in the log); settings keep fees, host,
    screen, notifications, sync and asked-permission keys, with `scan_in_background` renamed `background_enabled`.
- Engine `eval.Live`: `signal` (a call on the newest closed candle, entering at the forming candle's open, regime gate, ATR
  sizing: 1 ATR = 1% of the paper account, at most 20%) and `close` (the shared fill rules; an exit that fills at the forming
  candle's open, a signal exit or a gap through the stop, is reported at once instead of a day later). `Fills.exitTime` makes
  the backtest and the live runner time a fill at an open the same way. `LiveEqualsBacktestTest` replays three golden coins
  day by day and requires every live call to equal its backtest trade, both ways.
- `StrategyRunner` (app): refresh the pair list, bring every offered coin's daily history up to date (the universe and the
  coming cross-coin strategies need them all; about one request per coin per day), choose the universe, close open calls,
  open new ones, notify. Busy rule as the backtest's: a coin is busy while a call of that strategy is open or its exit is at
  the very open a new call would enter at. Calls beyond 8 open are recorded with size 0 (tracked, not in the account), so the
  evidence keeps every signal. Only Proven and Promising strategies notify.
- Scheduling: an exact alarm 90 s after 00:00 UTC (Binance's clock) starts a unique one-time job; a 6-hour periodic job is the
  backstop; boot and app updates re-arm both. Decision: not "expedited" work, which on Android 10 and 11 must run as a
  foreground service; the exact alarm already gives the timing.
- Decision: Signal Lab 1's scanner and screens keep running beside the runner until M6 (the plan had the old service removed
  at M5). That keeps the phone usable at the M5 install and lets the v2 strategies collect live calls from day one.
- Known optimism, as in Signal Lab 1: a call enters at the forming candle's open even if the pass runs later in that candle;
  the exact alarm makes that seconds in practice.
- Test stabilisation in Signal Lab 1 code (removed in M6): Robolectric starts the real application, whose background start now
  also imports and arms the v2 alarm. `PatternLabels.labTitles` gets a synchronized setter so a background merge cannot write
  over a value set at the same moment; the long day-by-day replay in the old `LiveEqualsBacktestTest` gets a 5-minute limit;
  `AndroidAlarmsTest` counts only the old scanner's own alarm. A run with leftover Gradle test JVMs failed with Robolectric's
  native SQLite not loading; a clean run (`./gradlew --stop`) does not.
- R8 keeps Room's generated `LedgerDatabase_Impl` and `LedgerDao_Impl` (only unused members are stripped). Release APK 5.9 MB.
- Engine 174, app 763 tests pass; release build signed from `local.properties`.

### M6: the new screens, and Signal Lab 1 removed

- New look "Night desk" (the approved mockup, artifact RSHKrn1juGBc16HRA1wM9m): `view/theme`, shared parts in `view/common`,
  icons drawn from the mockup's own stroke paths (no icon library).
- Screens: Today (market line, new calls, open calls with what to do, leave-now rows, this week's exits), a call's page
  (chart with entry, safety stop and exit lines; the plan; why now), Ledger (account curve against random entries, period
  switch, by strategy and coin, Signal Lab 1 history, CSV), Strategies (tiers, on/off), a strategy's evidence (research,
  backtest on your coins at your fees and at double, live record, sources, exact rule), Markets (market cards, search,
  watched coins), a coin's page (chart, its calls, pin or exclude), Settings, and an Analyst placeholder until M8.
- Navigation Compose with typed routes; a bottom bar under 600 dp, a side rail from 600 dp, and from 700 dp Today puts the
  chosen call's page beside the list (the Fold7's inner screen).
- State in `model/` over the ledger's Room flows; preferences (`ledger/Preferences`) for account size, costs, switches,
  notifications, host and futures data. The runner reads them: costs, and whether a strategy notifies (switched by hand, else
  Proven or Promising). Every call is recorded either way.
- Decisions made without asking:
  - Fonts: the phone's own sans serif and monospace with tabular figures. The mockup's IBM Plex would need font files
    downloaded; left for the owner to decide.
  - Calls from strategies that are off are hidden from Today but still recorded and still in the Ledger, marked "Off Today".
  - A strategy's live line says "About K more calls before a live verdict" with K counted to 100 calls; M7 replaces this
    with the minimum track record length.
  - On first start after the update: Signal Lab 1's alarm is cancelled and its five notification channels removed (once),
    and a pass runs at once if none ran in the last day. The notification permission is asked once on Android 13+.
- Removed: Signal Lab 1's `ui/`, `state/`, `scan/`, `analyst/` and `sync/` packages, its record classes, the Learn pages and
  Mermaid, the foreground scan service and its permissions; in the engine `Signals`, `Candlesticks`, `LabPatterns`,
  `LiveScan`, `VariantLabels`, the learned exit and end-of-day rules. Drive sync returns in M9 on the new ledger.
- Tests: the old screen, state, scanner, sync and lab tests go with their code; golden, fingerprint and exit tests keep their
  numbers exactly (the classic scorecard path is unchanged). New `ScreensTest` and `ModelsTest`.
