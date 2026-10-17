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

### M7: five more strategies, vetoes, evidence, tiers, and the rule language

- New built-ins (docs/strategy-research.md): Momentum 2w, Leaders (weekly ranking, needs at least 8 coins), Near high,
  Quiet dip and Hourly trend, beside Breakout and Trend timer: seven in all. Each has its plain words, sources and exact rule
  in `model/StrategyTexts`.
- Vetoes on every entry, backtest and live alike: signal-day volume over 5× its 30-day median together with a range over
  3 ATR. A vetoed signal is not recorded as a call. The liquidation-spike veto waits for enough stored futures data.
- `eval/Evidence`: per-call Sharpe moments, probabilistic and deflated Sharpe, minimum track record length, a stationary block
  bootstrap and the probability of backtest overfitting (combinatorial cross-validation). `eval/Tiers` turns the backtest at
  the person's fees and at double, the live record and the number of strategies ever tried into a tier and a sentence.
- The runner now wakes 90 seconds after every hourly close. The daily pass runs once per daily close (setting
  `daily_pass_day`); the hourly pass runs Hourly trend on the universe's hourly candles (100 days kept). Tiers are
  recomputed after each daily pass and stored as `tier.<id>` and `tier_reason.<id>`; Today, Strategies and notifications
  read them. A strategy switched by hand keeps its switch.
- The live line on Ledger and the evidence page now counts the calls still needed with the minimum track record length,
  never fewer than 100 in all.
- The rule language for custom strategies (`engine/strategy/dsl/Rule.kt`): a bracketed text form the engine parses, checks
  and prints back exactly, with 30 operations that can only read the candle they are on and earlier ones. Limits: windows and
  trades up to 500 candles, 120 operations, 12 levels, timeframes 1h, 4h and 1d only. Decided without asking: a text form
  instead of JSON, because the engine has no JSON library and the analyst can write it as one string; an entry condition that
  holds for several candles enters once. The builder screen and the analyst's use of it come in M8.
- Decisions made without asking:
  - The backtest ignores live universe ranks: it runs on whichever coins it is given.
  - The hourly strategy is only judged once hourly candles exist.
- Finding: on the 12 golden coins only Trend timer reaches a tier that makes calls (Promising). Breakout and Momentum are
  Watch, Leaders is Failing, Near high, Quiet dip and Hourly trend stay Testing. Today will show few calls until the owner
  switches strategies on or live calls build a record.
- Engine 123 and app 172 tests pass (2 skipped); release APK 4.2 MB.

### M8: the analyst

- Claude through Anthropic's official Java SDK (`com.anthropic:anthropic-java` 2.34.0, OkHttp client), on the owner's own
  API key. Default model Claude Opus 5.5, Claude Sonnet 5.5 selectable; thinking is always on for Opus 5.5, so no thinking
  setting is sent. Each request streams; the system prompt (with the rule language reference) is cached.
- Decided without asking: a hand-written loop rather than the SDK's beta tool runner, because the screen needs text as it
  streams, a search the server pauses (`pause_turn`) must be resumed, and the monthly budget is checked before every
  request. Client tools use eager input streaming; the phone checks every tool input against its schema before running
  it and answers a bad one with an error result. At most 12 requests and 4 resumes per run.
- Tools, run on the phone: `market_state`, `ledger`, `list_strategies`, `strategy_evidence`, `candles`, `derivatives`,
  `backtest`, `propose_strategy`, `suggest_call`, `flag_context`; plus Anthropic's `web_search` (5 per request) and
  `web_fetch` (3). The analyst cannot trade, change settings or record a call.
- Every rule the analyst backtests is registered in the strategies table under an id made from its canonical text, so it
  counts once toward the number of strategies tried. A proposal is saved as a custom strategy with a report; it runs only
  after the owner starts it in Strategies (`custom_live.<id>`), in Testing, forward-only.
- `make_call` became `suggest_call`: a suggestion waits on Today (at most 3, 3 days); a recorded one enters at the next daily
  open after it was recorded, as a call of the strategy "Analyst", with its own stop, target and time limit. The engine's
  exit plan gained an optional target (filled at the target or at a gap above it; the stop is still assumed first), and the
  analyst's random entries use the same stop and target in ATRs.
- The key: Android Keystore AES-GCM, in the no-backup folder; never synced, logged or sent anywhere but Anthropic.
- Spend: a monthly meter (`analyst_spend.<yyyy-MM>`) priced from each response's usage (Opus 5.5 $4/$20 per million tokens,
  Sonnet 5.5 $2/$10, cache reads $0.20, searches $0.01); default budget $20 a month.
- Decided without asking: the analyst's key, model, budget, brief and review live on the Analyst tab; Settings points there.
  The conversation keeps text only (the last 16 messages go with a question); the last 50 runs are kept for the "What was
  sent" page. The daily brief and weekly review are off by default and run as WorkManager jobs after a pass, with a
  network.
- Without a key, the Analyst tab shares a question with a data snapshot to the Claude app (read only).
- Fixed: the rule language read "1d" as the number 1 (Java's number parser accepts a d suffix), so `(timeframe 1d)` failed
  after a round trip. Numbers are now read with a strict pattern.
- Release: R8 keeps the SDK's message models and Jackson's binding whole; the APK grows from 4.2 MB to 13.4 MB.
- Not verified yet: a real run against the Claude API (it needs the owner's key) and the shrunk SDK on the phone.

### M9: sync, release preparation

- Sync between devices returns on the new ledger, through the same hidden Drive folder, Google authorization and OAuth
  client as Signal Lab 1 (`sync/`: `DriveFolder` and `DeviceSync` ported, `LedgerSync` and `SyncEngine` new). Each device
  writes only its own file; Signal Lab 2 writes `device-<id>.v2.json.gz` in format 2 and reads every device's file, Signal
  Lab 1's format 1 included. Signal Lab 1 sees format 2 as a record it cannot read and leaves it alone (it says a device
  needs the newest version). The device id carries over from Signal Lab 1; a device that had sync on keeps it on.
- Format 2 carries the strategies registry, every call with its exit, custom strategies with their stops, reports, pins and
  the settings that follow the owner. Calls match by strategy, coin, signal candle and scope, so two devices that make the
  same call keep one. A Signal Lab 1 lab pattern's call travels under its sync id and comes back under this phone's id.
- Format 1 is read the way the importer reads Signal Lab 1's own file, so a trade already imported here is not added twice.
- Decided without asking:
  - Synced settings: account size, costs, data source, futures data, strategy switches, custom strategies running, the
    analyst's model and budget, its suggestions and context lines. Not synced: notifications, the brief and review
    switches (so only one device pays for them), tiers (each device computes them), the spending meter, sync's own state.
  - Pins are merged as a union: removing a pin is not synced. Signal Lab 1's settings in a format 1 record are ignored.
  - Rounds run on start, on returning to the app, after each pass and shortly after anything worth sending changes, at
    least five minutes apart; the setting changes that trigger a round are only the synced ones.
- The updater needs no change: it already ignores drafts and pre-releases. The release workflow now publishes a
  pre-release when the version's `CHANGELOG.md` heading ends in "(pre-release)", and 2.0.0 is marked so: Signal Lab 1.7's
  updater offers only the latest full release, so no phone is offered 2.0.0 before it is verified on the Fold7.
- `CHANGELOG.md` has the 2.0.0 section. The `v2.0.0` tag is not made: it waits for the owner (stop point 3).

### M10: the analyst on the owner's PC

- Approved with "approve" on 2026-10-17, after the owner said their plan has no API credit. The owner's consumer login is
  never used by Signal Lab: the PC runs Anthropic's own Claude Code, signed in as it already is, and Signal Lab only talks to it.
- `analyst-host/`: a Kotlin program on the JDK alone (its HTTP and HTTPS servers are the JDK's). For each question it starts
  `claude -p` with stream-json output, the analyst's instructions as the system prompt, `--restricted` (no settings files, so
  none of the owner's hooks, plugins or global instructions), `--tools WebSearch,WebFetch`, the bridge as the only MCP server
  (`--strict-mcp-config`), `--permission-mode dontAsk` with only those tools allowed, `--max-budget-usd 5` as a runaway stop,
  and no saved session. The conversation so far goes in the input as a transcript.
- Decided without asking:
  - Claude Code's command line, not the Agent SDK: there is no Agent SDK for the JVM, and the CLI is what is installed.
  - `--safe-mode` is not used: a live run showed it also drops the bridge's MCP server. `--bare` is not used: it reads only
    an API key, never the plan's login. A live check showed restricted mode keeps the owner's global CLAUDE.md out.
  - The tools stay on the phone: the bridge (MCP over HTTP on 127.0.0.1, with its own random token) forwards each call
    to the phone through the run's event stream and waits up to 60 seconds for the answer. The PC keeps no copy of the
    ledger or candles, so there is nothing to sync and no JDBC port of the candle store.
  - Arguments reach Claude Code on Windows' command line, where Java does not escape quotes: the MCP configuration goes
    in a file, and straight quotes in the instructions become typographic ones.
  - One question at a time (409 otherwise). The phone's Stop ends the run at once (`POST /runs/<id>/stop`); a dropped
    connection is noticed at the next keep-alive, within five seconds, and Claude Code is killed.
  - Pairing: the host prints its LAN address, a 10-character code and the end of its certificate's SHA-256; the phone shows
    the fingerprint it received and pins it once the owner confirms the two match. The code is sealed like the API key; the
    address and pin are settings that are never synced. A wrong code waits longer each time.
  - Runs on the PC cost nothing on the API budget; the run's log notes the plan's limits used (from Claude Code's rate-limit
    event) and what it would have cost at API prices.
  - The brief and the review, with the PC off and no key, are queued again after the next pass instead of failing.
- Tests: host 8 against a stand-in Claude Code (recorded real output, real bridge calls), plus a live test run once through
  real Claude Code on the owner's login (the tool was called and its answer used); phone 5 for the PC route, fallback and
  pinning with real certificates, 3 screen tests. Engine 128, host 9, app 213 pass; release APK 13.5 MB.
- Not verified yet: a run from the phone itself to the PC over the home network.
- Verified on the Fold7 2026-10-17: paired with the host on the owner's PC (fingerprint checked by the owner) and answered
  a question through it in 18 seconds, with the app's tools. Fixes found on the way:
  - The JDK's HTTPS server looks up the name of every client; on Windows a phone's address has none, and the lookup took
    five seconds per connection, so the phone's four-second code check failed and showed "refused the pairing code". The
    host now points the JDK at an empty hosts file, so lookups answer at once.
  - The start script calls Gradle's wrapper and the host by full path (this shell does not run programs from the current
    folder by bare name), and `.cmd` files keep CRLF line endings.
  - The host's watcher thread no longer prints an InterruptedException when a run ends normally.
  - Port 8765 was held by another program on this PC, so the host ran on 8766 (`--port 8766`).
- Open: when the phone's code check fails for any reason it says the code was refused; it should say why. And the
  analyst noticed that Trend timer's tier reason says "not yet at double the fees" while its evidence shows +4.7% per call
  at double fees: the rule likely needs confidence, not just a positive mean, and its words should say so.

### 2026-10-17: two open items from the host pairing, fixed (approved)

- Pairing check says why: `HostClient.verify()` does the `/check` call and throws a sentence for each failure (code refused,
  no answer in four seconds, a different PC's certificate, another status, not reachable). `check()` is now
  `runCatching { verify() }.isSuccess`, and `HostPairing.probe` calls `verify()`, so "refused the pairing code" appears only for a 401.
- Tier reason: the Promising sentence no longer says "not yet at double the fees" whenever Proven is missed. It says "not ahead
  at double the fees" when the mean at double cost is not positive, and "still ahead at double the fees, but not yet confident
  enough after allowing for the N strategies tried" when it is positive but the deflated Sharpe is not above 0.95. The tier rules
  and thresholds are unchanged: Trend timer's +4.7% at double fees is a positive mean that does not clear the deflated Sharpe bar.
- Tests: `TiersTest` for both sentences; `AnalystPcTest` for a 500 and a slow answer during pairing.

### 2026-10-17: CI failure on the first push of v2, fixed (approved)

- `AnalystPcTest.pairingPinsThePcsCertificateAndNoOther` failed with a `BindException` on GitHub's runner in both the `main` CI
  run and the `v2.1.0` release run (213 tests, 1 failed), and passed locally. The test restarts a stand-in PC with another
  certificate on the same port. First guess (wrong): the runner had not released the port yet, so the rebind got a retry for
  five seconds; CI failed again the same way. `app` unit tests now print full failure traces, and the trace said
  `java.net.BindException: Address already in use` from `MockWebServer.start(port)`, still after 50 tries.
- Cause (inferred from the trace, not run on Linux here): the first stand-in started on port 0, where MockWebServer does not set
  SO_REUSEADDR, and on Linux a port its sockets (including closing and TIME_WAIT ones) held cannot be bound again for a minute. Windows
  allows it, so the test passed locally. The test now starts the first stand-in on a named free port, which makes MockWebServer set
  SO_REUSEADDR, and the retry is gone. No 2.1.0 release had been published.
