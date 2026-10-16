<!-- Approved on 2026-10-16. Copied from the planning session; this is the plan Signal Lab 2 is built to. -->

# Signal Lab 2: rebuild proposal

## Context

Signal Lab 1.7 is a 32,500-line Kotlin/Compose app that watches Binance spot coins, turns 13 families of
research-era technical signals into paper trades and keeps a statistically strict scorecard. The owner's
verdict after seven releases: they understand nothing from it, get no benefit from it, and the UI is
rubbish. They want it rebuilt from scratch, with the pattern set re-derived from fresh evidence, the
stored records kept, and zero UI/UX carried over. This replaces the five-phase "engine overhaul" mission
(its Phase 1 audit, `docs/engine-audit.md`, is reused as input).

Decisions already taken by the owner (2026-10-16):
- Purpose: **"tell me what to do" first, backed by an honest "does it work" ledger** that decides what is shown.
- Patterns: **re-derived from deep research** into what actually works in crypto; the app's strategy set tracks that research.
- Data: keep what the new version needs (trades, exits, lists, reports, candles); **nothing from the current UI/UX survives**.
- Audience: the owner now, on a Galaxy Z Fold7; publishable later.
- Charts under one hour: **no calls on them** (viewable in Markets only); scanning is 1d, 4h, 1h.
- **Mockup first**: an interactive HTML mockup of the screens is approved before any Compose is written.
- **Same repo, branch `v2`**, same applicationId `com.ikverse.signallab` and signing key, so the new APK
  installs over the old one and imports its data.
- **An in-app Claude analyst agent** (added 2026-10-16): a live session inside the app that analyses the
  collected data, follows crypto news and world events, and proposes new strategies; authenticated with
  the owner's own Claude account via an API key whose usage is covered by the plan's monthly API credit.

## Why the current app gives nothing back (diagnosis)

Everything below was verified in source by three read-throughs on 2026-10-16.

1. **The unit the user sees is a paper trade, not a decision.** "Today" is the list of open paper trades
   (`ui/TodayScreen.kt:40`). With 13 families x up to 7 timeframes x up to 150 coins, correlated variants
   (trend_ma20, donchian20, donchian10 all fire on the same breakout) open several trades on one move. Nothing
   ranks them, nothing says which one matters, and nothing says what to do next.
2. **The verdict is unreachable.** "Working" needs n >= 30 live trades per variant per timeframe, cluster
   t >= 3.0 **and** Holm-corrected p < 0.05 across every variant ever registered (`engine/Statistics.kt:93-121`).
   With ~60 live variants the bar is t ~ 3.3+, which a real edge of 1-2% per trade cannot clear for years.
   So "Does it work?" says "Too early to tell" or "No better than guessing" for everything, forever. Backtests
   on the stored history are never used as evidence for built-ins; only live trades count.
3. **The patterns were never validated.** Golden tests prove the Kotlin equals the retired Python, not that the
   patterns earn anything. There is no out-of-sample or walk-forward test anywhere (audit S1). Live exits
   (trail, learned) were never backtested; the "learned" target is the median past rise, so half of trades hit
   it by construction (audit S5). Candlestick patterns (harami, hikkake) and one-candle intraday momentum have
   no published support in crypto.
4. **Five verdict vocabularies at once** (engine words, PlainWords, LabProgress, firmness tiers, "practice /
   paper / pretend trade") and a hard rule against saying what to do, so even a good signal reads as a riddle.
5. **One trade appears in five places** (Today, Coins > Details, All trades, Alerts, Lab test page); the Lab is
   managed in two places (Lab tab and Ask Claude); the Claude loop goes out through the share sheet and back
   through a paste box on a different screen; 26 Learn pages explain a model that needs a manual.
6. **The Lab cannot express the app's own patterns** (no multipliers, lags, OR, time-of-day, ranks), rations
   tests (10 running, 15 per 30 days), and needs 30 live trades per chart before it says anything.
7. **Watchlists are a chore**: hand-built lists of <= 30 coins with four caps, when the useful universe is
   simply "the liquid coins" and can be derived from the exchange daily.

What is genuinely good and worth keeping underneath: closed-candle discipline and no-look-ahead invariance
tests, the append-only record, the deterministic counter-based random baselines, cluster-robust statistics,
the next-open entry / stop-first / gap-at-open fill model, the Binance client's pacing, the signer-checked
updater, Drive sync, and the release pipeline.

## The product: Signal Lab 2

One sentence: **a daily briefing of concrete calls, each backed by a strategy whose evidence you can see, with
a ledger that proves or disproves every strategy over time.**

### Core objects (the whole vocabulary; nothing else is user-facing)

| Object | Meaning |
|---|---|
| **Market** | BTC regime (trend up/down, volatility high/low, funding hot/cold) in one line. Sets which strategies are allowed to fire. |
| **Strategy** | A named, fixed rule: universe filter, entry, exit, sizing, allowed regimes, timeframe. Comes with an **evidence card**: research citation, the app's walk-forward backtest on the user's coins, and the live record. Each has a confidence tier derived from that evidence. |
| **Call** | What to do: coin, Long/Short, entry, stop, target, time limit, size (% of a notional account, volatility-targeted), one-line reason, strategy name, confidence. **One call per coin per direction**; when several strategies agree the call says so and gains confidence. |
| **Ledger** | The paper account: every call from entry to exit, equity curve, per-strategy and per-coin results, versus the random baseline. Append-only, exportable. |
| **Verdict** | Per strategy, three columns: *Backtest* (in-sample), *Out-of-sample* (walk-forward on held-out periods), *Live*. Plain sentence plus the numbers. Never a single word. |

### Screens (five tabs plus a gear)

1. **Today**: Market line at the top. Then **New calls** (fired since you last looked), **Open calls** (each
   with live P&L in R, and the next instruction: "hold", "stop now at 136.1", "target 154.7 within 2 days"),
   **Closed today**. A call card: coin + direction + confidence badge, entry / stop / target with % and R,
   size, reason, strategy. Tap: chart with levels, strategy evidence card, "why now" explanation, full history
   of this strategy on this coin. Notifications mirror the cards: "SOL long: entry 142.30, stop 136.10,
   target 154.70" and "Exit SOL: stop hit".
2. **Ledger**: equity curve (paper account, after costs) with the random-baseline curve beneath it; strategy
   table (calls, win rate, avg R, expectancy, vs random, verdict); coin table; filters; CSV export.
3. **Strategies**: the library. Each strategy: on/off, evidence card, parameters shown (not editable),
   "why it should work" paragraph, walk-forward results on the user's coins, live record. **Custom
   strategies** (Lab 2) live here too: build from a richer vocabulary (AND/OR, lags, multipliers, regime filter,
   cross-sectional rank), backtest walk-forward in seconds, promote to live. Claude can draft one from a
   sentence (hand-off to the Claude app as today, or an optional API key).
4. **Markets**: the universe (automatic: top-N liquid USDT pairs, stablecoins and leveraged tokens excluded,
   with pins and excludes), live prices, the chart (KLineChart, kept as a library; every page around it new).
5. **Analyst**: the in-app Claude agent (chat, daily brief, weekly review; see its own section below).
6. **Settings** (gear): account size and costs, scanning and battery, data source, Claude key and spend
   cap, sync and backup, updates.

Wording: the app says Long/Short, entry/stop/target, "exit now". A standing one-line disclaimer on first run
and in Settings replaces the old ban on buy/sell words. One vocabulary across screens, notifications and
exports.

### How the verdict becomes reachable

- Fewer, better strategies (6-10 built-ins, research-backed) so the multiple-testing penalty is small.
- Evidence from three sources instead of one: backtest, walk-forward out-of-sample on the stored history
  (hundreds of trades on day one), and live. The live column confirms; it is no longer the only evidence.
- Pooled across coins and timeframes per strategy, with cluster-robust t and a block bootstrap; the
  "losing" side stays easy to reach so bad strategies are demoted fast.
- Confidence tiers map to what the user sees: a strategy with no out-of-sample support never produces a
  call on Today (it can still run silently and accrue a ledger).

## What the research says (three threads, 2026-10-16; full briefs go into `docs/strategy-research.md`)

Fee frame: a retail Binance spot taker pays about 0.15-0.20% round trip on majors and 0.30-0.45% on top-50
alts. Every finding below was judged against that.

**Survives (worth building on):**
- **Daily trend / breakout rules.** Donchian-style trading-range breakouts (50-200 day) have the clearest
  forecasting power on daily BTC (Gerritsen et al. 2020) and, after data-snooping correction across 15,000
  rules, keep out-of-sample predictability on altcoins though not on BTC (Hudson & Urquhart 2021). Price
  above a 20-100 day SMA as a long/flat timer improves Sharpe mainly by avoiding drawdowns (Detzel et al.
  2021, out-of-sample; Grayscale 2023). Breakeven switching cost 1.1-4.5%: fee-robust.
- **Time-series momentum at 1-4 weeks** (Liu & Tsyvinski 2021 RFS; Han, Kang & Ryu 2023 at 15 bp fees).
  Lives in large caps, dies in bear markets; holding beyond ~4 weeks reverses (Dobrynskaya 2024).
- **Cross-sectional momentum, 2-3 week formation, long top quintile** (Liu, Tsyvinski & Wu 2022 JF). Gross
  spreads were large; the short leg is unshortable micro-caps; net post-2022 evidence is weak. Long-only in
  liquid coins with a 2-week hold is what survives, and it is decaying: tier "watch".
- **Large liquid coins near their 1-week high keep going; small illiquid ones revert** (Fičura 2023,
  Zaremba et al. 2021). Single working paper but consistent with the momentum literature.
- **Low-volume reversal**: a coin down on below-median volume rebounds over the next day to week; the
  effect concentrates in mid-caps and is robust to linear costs (Bianchi, Babiak & Dickerson 2022).
- **Regime**: anomaly returns concentrate in bull markets (Fieberg et al. 2024; Han et al.). A BTC-above-MA
  gate is theoretically motivated but never tested in crypto: build it, tag every call with the regime, and
  let the ledger answer.
- **Volatility scaling** of position size cuts momentum crashes (Grobys et al. 2025; Harvey et al. 2018).
- **Exits**: for trend entries, time stops and wide channel exits keep more edge than tight trailing stops
  (Setup4alpha, 105k trades; StockCharts); stops only add value where momentum exists (Kaminski & Lo 2014).
- **Vetoes from flow data**: a pump reverses from 70 s and is gone within an hour (Li, Shin & Wang JFQA);
  abnormal volume predicts lower returns in crypto (Garfinkel et al. 2025); the 30 days after a top-5%
  liquidation spike average below the unconditional return (K33). Derivatives data is for vetoes and
  context, never entries. Funding and basis extremes do not predict direction at 8h-30d.

**Dropped by the evidence:** candlestick patterns (68 tested, no value, IEEE 2021); MACD or any TA on 1h
and faster as a standalone (fees exceed the edge; Cohen 2023; Quantpedia 2025 MACD net negative); anything
on 1m-15m bars with taker fills (a BTC 1m bar's range is below the fee); RSI(2); hour-of-day and
overnight-session tilts (decayed since 2022, fee-dominated); fading big moves (contrarian loses, Caporale
& Plastun 2019); funding-rate z-score entries; OI x price quadrants, long/short ratio thresholds,
"volume-confirmed breakout" multipliers (vendor claims only); BTC-leads-alts at the hourly scale (near
zero, Khan 2026; sign negative in Jia et al. 2023).

Consequence for the product: **the app scans daily, 4h and 1h candles only.** The 1m-30m wake-locked
loop, the fast-chart caps and the battery exemption go away. Calls last days to weeks, which is also the
horizon at which a person can act on a notification.

## Built-in strategies v2

All long-only on Binance spot USDT pairs, entry at the next candle's open, paper-sized so one ATR(14) move
equals 1% of the notional account (cap 20% per call, at most 8 open calls). Every strategy carries a
`regime` tag (BTC above/below its 200d SMA; realized-vol tercile) on each call so the gate can be tested
instead of assumed. Exits are fixed per strategy and shared with the random baseline.

| # | Strategy | Timeframe | Entry | Exit | Tier at launch | Evidence |
|---|---|---|---|---|---|---|
| 1 | **Breakout** | 1d | close > highest high of prior 50 days (alts also 20) | close < lowest low of prior 20 days, or 60-day time stop; safety stop 3 ATR | Proven on alts, Promising on BTC | Gerritsen 2020; Hudson & Urquhart 2021 |
| 2 | **Trend timer** | 1d | close crosses above SMA(50); BTC and top-10 only | close below SMA(50) at a daily close | Promising | Detzel et al. 2021; Grayscale 2023 |
| 3 | **Momentum 2w** | 1d | 2-week return > 0 and > its 60-day median; liquid top-30 | 2-week time stop; safety stop 3 ATR | Promising | Liu & Tsyvinski 2021; Han et al. 2023; Dobrynskaya 2024 |
| 4 | **Leaders** (cross-sectional) | 1d, weekly on Monday close | top 20% of the universe by 3-week return | 2-week time stop | Watch | Liu, Tsyvinski & Wu 2022; decaying post-2022 |
| 5 | **Near high** | 1d | close within 1% of its 7-day high, liquid top-30, not already in a Breakout call | 1-week time stop | Testing | Fičura 2023 |
| 6 | **Quiet dip** (reversal) | 1d | 1-day return < -5% on volume below the 30-day median; ranks 10-80 by volume | 5-day time stop, no price stop | Testing | Bianchi et al. 2022; Zaremba 2021 |
| 7 | **Hourly trend, daily-filtered** | 1h (entry) + 1d (filter) | 1h close crosses above EMA(50) while the daily SMA(50) slope is up | 1h close below EMA(50), 48-bar cap | Testing | Quantpedia 2025 (gross only) |

Vetoes applied to every entry: pump (5-minute equivalent move >= 5% on >= 10x normal volume, kept from
`Warnings.pump` but computed on 1h), abnormal volume on the signal candle (> 5x 30-day median and range
> 3 ATR), stablecoin or leveraged token, listed < 90 days, and a BTC liquidation spike in the last 24h
(when futures data is on). Gate: strategies 3-5 only fire when BTC closes above its 200d SMA; 1, 2, 6, 7
fire in both regimes and are tagged.

Tiers and what they mean on Today:
- **Proven**: published support + positive on your coins (probabilistic Sharpe > 0.95 at 2x cost) + live
  ledger not contradicting. Produces calls.
- **Promising**: published support + positive on your coins, or live PSR > 0.95. Produces calls, badge says
  so.
- **Watch / Testing**: single-source or custom; runs silently into the ledger; no call on Today unless the
  user switches it on in Strategies.
- **Failing**: live or on-your-coins excess below random with cluster t <= -2: switched off, shown as such.

Tiers are recomputed nightly from the three evidence columns; the rules are fixed numbers shown on the
evidence card.

### Evaluation protocol (replaces the single Holm verdict)

1. Every strategy and custom rule is registered before it is backtested; the registry count N is the
   multiple-testing denominator forever.
2. Random baseline kept as is (same coin, +-90 days, same regime, identical exit, 20 draws), excess with a
   stationary block-bootstrap 95% CI (2,000 resamples).
3. Per strategy: per-trade Sharpe, probabilistic Sharpe vs 0, deflated Sharpe with N, minimum track record
   length shown as "needs about K more calls"; no verdict below 100 calls or below MinTRL.
4. Custom strategies get combinatorial purged cross-validation (8 blocks, 70 splits) with the probability
   of backtest overfitting shown; PBO > 0.3 is flagged.
5. Every number shown at 1x and 2x the assumed cost; a strategy whose sign flips at 2x is "fee-fragile".
6. Daily history is extended from 400 days to the full listing history so the "on your coins" column has
   thousands of candles per coin from day one.

## The in-app analyst: a live Claude agent inside Signal Lab

### How it signs in (the part that must be done right)

Using a consumer Claude token (the claude.ai or Claude app login) inside another app is against Anthropic's
terms and was enforced in 2026: Free/Pro/Max OAuth tokens may not be used in non-official products, and
subscriptions stopped covering third-party tools in April ([winbuzzer](https://winbuzzer.com/2026/02/19/anthropic-bans-claude-subscription-oauth-in-third-party-apps-xcxwbn/),
[alternativeto](https://alternativeto.net/news/2026/2/anthropic-officially-bans-using-subscription-authentication-for-third-party-claude-use)).
What replaced it is the legitimate path, and it is what the app uses: **since May 2026 paid Claude plans
include a dedicated monthly API credit for programmatic use** (reported as Pro $20, Max 5x $100, Max 20x
$200 per month, no roll-over, overflow only with extra-usage billing at API rates;
[VentureBeat](https://venturebeat.com/technology/anthropic-reinstates-openclaw-and-third-party-agent-usage-on-claude-subscriptions-with-a-catch)),
and Anthropic's own support page, updated 2026-10-07, says those credits cover the Agent SDK, `claude -p`,
the Claude API and Managed Agents ([support.claude.com](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan)).

Decided 2026-10-16 (owner is on **Max**, PC at home only sometimes on): **two routes, API key in the
phone as the default, a PC agent on the subscription as the fallback.**

- **Route 1, default: API key on the Max plan's monthly credit.** The owner creates an API key in the
  Claude Console under their own account (Settings in the app walks through it: open the Console, create a
  key, paste). The app stores it in Android Keystore-backed encrypted storage (never in the APK, never
  synced, never in backups). Every call is billed to the plan's monthly credit first; the app shows the
  month's spend and the owner sets a cap. If the Console shows no credit yet (one October guide says it is
  still rolling out), pay-as-you-go applies until it appears, at roughly ten dollars a month for the
  scheduled runs.
- **Route 2, fallback: the PC analyst host.** A small JVM program in this repo (`analyst-host/`) that runs
  the **Claude Agent SDK** signed in with the normal Claude Code login, which Anthropic's support page says
  a plan covers. It exposes the same chat contract to the phone over the home network or Tailscale with a
  shared secret, and runs the engine's backtest tools itself (the engine is plain Kotlin). The phone uses
  it when it is reachable and the owner has chosen it; otherwise it uses Route 1. Reports and proposals
  flow back into the phone's ledger through the same `propose_strategy` contract.
- **Route 3, zero-key: the share-sheet hand-off** to the Claude Android app, kept for users with neither.

The phone never holds a consumer login token on any route.

### What the agent is

On both routes the phone's Analyst screen talks to one `AnalystTransport` interface, so the screens, the
tool handlers and the ledger contract are shared; only the transport differs (SDK call from the phone,
or HTTP to the host). On Route 1 it is a **Claude Opus 5.5** session (`claude-opus-5-5`, adaptive
thinking, streaming, server-side fallback on refusals; Sonnet 5.5 selectable in Settings for cheaper
runs) with the app's own data exposed as tools,
plus Anthropic's server-side **web search and web fetch** tools for news and world state. It runs on
the phone through the official Anthropic Java SDK (`com.anthropic:anthropic-java`), or raw OkHttp +
kotlinx.serialization if the SDK does not run on Android (checked in M0). Its system prompt makes it a
skeptical crypto quant: use only the app's numbers, name the trade count behind every claim, never
present a hypothesis as an edge.

Tools the agent can call (all executed on the phone, results returned to the model):

| Tool | What it returns or does |
|---|---|
| `market_state` | BTC regime, vol tercile, funding, basis, OI change, top movers, the universe |
| `ledger(strategy?, since?)` | calls, exits, excess vs random, PSR/DSR, MinTRL, by regime and coin |
| `strategy_evidence(id)` | the evidence card: citation, on-your-coins backtest, live record, tier |
| `candles(symbol, tf, n)` / `derivatives(symbol, n)` | raw series for a question |
| `backtest(definition)` | runs the v2 DSL rule through the engine with cost stress and CPCV; returns the table |
| `propose_strategy(definition, thesis, sources)` | pre-registers a custom strategy (Testing tier, forward-only) and attaches the thesis and the news sources it cited; the owner sees it in Strategies with one-tap "run live" |
| `make_call(symbol, side, entry, stop, target, limit, thesis)` | records a **discretionary call** under the strategy "Analyst" after the owner confirms it on Today; the agent's own judgment thus gets a ledger and a verdict like any other strategy |
| `flag_context(text, until)` | puts a dated context line on Today ("FOMC Wednesday", "exchange outage") |
| `web_search`, `web_fetch` (server-side) | crypto news, macro calendar, exchange announcements, research |

### How it is used

1. **Chat** ("Analyst" tab, replacing the Claude hand-off screen): a persistent conversation (history
   kept on the phone, compaction on long threads). Ask anything: "why did Breakout fire on SOL", "what in
   the news could hurt open calls this week", "design a rule for post-liquidation reversals and test it".
2. **Daily brief** (after the daily scan, optional, Wi-Fi only if set): the agent reads `market_state`,
   the day's new and open calls and the news, and writes a short brief with any `flag_context` lines.
3. **Weekly research review**: the agent reviews the ledger, reads research and news, retires or proposes
   strategies through `propose_strategy`, and explains its reasoning. Every proposal is a pre-registered
   hypothesis judged by the same evaluation protocol as the built-ins; nothing goes live without a tap.
4. **Explain a call**: one tap on any call card.

### Cost control, shown in the app before every run

Per-run token budget (task budgets beta), prompt caching of the system prompt and data snapshot, a
monthly cap in Settings with the running total from `usage`, and a cost estimate on each button. Rough
sizes at Opus 5.5 rates ($4/$20 per million tokens): a daily brief of ~30k input and ~3k output tokens is
about $0.20; a weekly review with web research about $1; so roughly $10 a month before chat, inside a
Max plan's $100 credit and a Pro plan's reported $20. Sonnet 5.5 halves it.

### What leaves the phone

Only what the chosen tool returns, only when a run is triggered or scheduled by the owner, only to
Anthropic's API. The Analyst tab has a "what was sent" view per run. The Settings data note states this
plainly; the old "no AI of its own" sentences go.

## Architecture of the rebuild

### What is kept, deleted, new

| | Kept (moved, minimal change) | Deleted | New |
|---|---|---|---|
| **engine** | `Candles.kt`, `CandleClock.kt`, `Indicators.kt`, `Random.kt`, `Statistics.kt`, `PaperTrading.kt` (fills, simulate/resolve, regime), `Exits.kt` TRAIL/HELD/CLASSIC, `Scorecard.matchedRandom` + `runVariant` generalised to take an exit rule, `Watchlists.kt` CoinFilter, `SignalKey` (moved out of `Signals.kt`), golden resources | `Signals.kt`, `Candlesticks.kt`, `LabPatterns.kt`, `LiveScan.kt`, `VariantLabels.kt`, LEARNED exit, `endsTheDay`, `EngineConfig.isForwardOnly` by name | `strategy/` (`Strategy` interface, registry, 7 built-ins, vetoes, `dsl/` expression tree for custom strategies), `eval/` (backtest, PSR/DSR/MinTRL, stationary block bootstrap, CPCV/PBO, cost stress, verdict tiers) |
| **app data** | `BinanceClient` (+ futures host), `CandleStore`/`CandleSync`/`HistoryManager` (daily trim exempts 1d; loop to listing date; `quote_volume` column from kline field 7), `UniverseRepository` reworked to top-N by 30-day quote volume | `WatchlistRepository`, `TradeLog`, `LabStore`, `RecordDatabase` as live code (its v6 SQL is frozen as the importer's fixture) | Room `ledger.db` (calls, exits, strategies registry, custom_strategies, reports, pins, settings, import_log) with the same append-only triggers; `derivatives.db` (funding full history; OI, basis, ratios accumulated locally, in backup and sync because they cannot be re-downloaded past 30 days) |
| **app services** | `PermissionFlow` (battery exemption still useful on Samsung), `Notifier` channels idea, `update/` whole, `sync/` ported to format 2 (and reading format 1), `analyst/` hand-off | `ScanService` foreground loop, `AndroidAlarms` fast path, `PriceFeed` as a scan input (kept for Markets prices only) | `runner/` `StrategyRunner` + `ScanWorker` (WorkManager periodic as backstop) + one exact alarm at the daily close + boot receiver; `MY_PACKAGE_REPLACED` handler that cancels the 1.7.0 alarm (request code 100, action `com.ikverse.signallab.SCAN_ALARM`) and its status notification |
| **app ui** | `WebPool`/KLineChart as a library; `FoldMath` and the `WindowInfoTracker` flow from `MainActivity` | everything else under `ui/`, `state/Live*Model`, Learn pages and Mermaid (3.3 MB) | Navigation Compose type-safe routes, Material 3 + `material3-adaptive` `ListDetailPaneScaffold`, ViewModels, four tabs + Settings, new theme from the approved mockup |

One Gradle module `engine` stays (package split `core/`, `strategy/`, `eval/`; a source-scan test enforces
the layering like the existing `ui/` import ban); `java-test-fixtures` exposes `Golden`/`Synth` to app tests.
Manual DI (`AppGraph`) stays; no Hilt. Add: Room 2.8 + KSP, WorkManager 2.10, Navigation Compose 2.9,
kotlinx.serialization, material3-adaptive, lifecycle-viewmodel-compose. Avoid: Hilt, Retrofit, DataStore,
Room sqlite-bundled, Commons Math.

Pump veto note: `Warnings.pump` only works on 1m/5m candles, which are no longer stored. The v2 veto is
computed on the signal's own candle (1h or 1d): move >= 5% in one hour on >= 10x the hourly median volume,
or volume > 5x the 30-day median with range > 3 ATR on a daily candle.

Return series note: PSR/DSR need per-period returns. v2 keeps a daily paper-book return per strategy
(sum of open calls' marked-to-close moves, sized) alongside the per-call excess used by the baseline test.

### The importer (one-time, first launch of 2.0.0)

1. Open `signal_lab.db` through the frozen v6 migration SQL (upgrades anything older), checkpoint WAL,
   close, copy to `files/legacy/signal_lab.v6.<utc>.db`, record its SHA-256 in `import_log`; re-open
   read-only and read with plain SQL.
2. Mapping: `live_trades` -> `calls` (`source='v1'`, `archived=1`, `strategy_id='v1:'+variant`, null cost
   -> research cost with `cost_assumed=1`, null exit_mode -> classic/held as `ExitMode.fromStored` does);
   `live_exits` -> `exits` 1:1; trades without an exit -> `status='unresolved_v1'`; `variants` ->
   `strategies` registry rows (`retired=1`, counted in N); `lab_patterns`+`lab_stops` ->
   `custom_strategies` (`status='archived'`, `definition_v1` verbatim, translated to the v2 DSL later);
   `analyst_reports` -> `reports`; `watchlists`+`watchlist_coins` -> `pins` (one per symbol, tag = list
   name); `settings` keeps fees, host, notifications, screen, sync keys; `alerts` dropped (count logged).
3. Idempotent: one transaction, natural-key unique index on `calls(strategy_id, symbol, bar_time, scope)`,
   no-op when the SHA is already logged. The old file is never deleted in 2.x. Backup-to-file is offered
   on the first screen before the import runs.
4. The Drive format-1 JSON goes through the same importer, so a device still on 1.7.0 cannot corrupt a v2
   device; a v2 device writes format 2 under a new file name.

### Build order (branch `v2`; each milestone green on CI; version 2.0.0, versionCode 20000)

| # | Lands | Testable then |
|---|---|---|
| M0 | Branch, version bump, empty shell compiling Room/KSP/WorkManager/Navigation/serialization under AGP 9 built-in Kotlin, `testFixtures` on engine, CI unchanged; `docs/strategy-research.md` (the three briefs synthesised) and this plan committed | Build; proves the toolchain before anything depends on it |
| M1 | Engine groundwork, nothing deleted: `SignalKey` in its own file; golden paper-trade, fingerprint and exit tests read the research signals saved in `expected.json.gz` (or synthetic signals) instead of `Signals.kt`; `LayeringTest` forbids new `strategy/` and `eval/` code from using the five Signal Lab 1 files, which are marked for deletion in M6. (Changed 2026-10-16: deleting them here would have broken the 1.7 app code still on the branch until M6.) | Whole engine and app suites green |
| M2 | `Strategy` interface, Breakout + Trend timer, TRAIL/channel/time exits, generic invariance harness, `Backtest` over a panel | Invariance, fill parity, a backtest table on the golden coins printed by a test |
| M3 | Data port: quote volume, full daily history, top-N universe, futures client + `derivatives.db` | Candle half of `GoldenPipelineTest`; MockWebServer tests |
| M4 + M5 | `ledger.db` + importer + backup-first; `StrategyRunner`, `ScanWorker`, daily alarm, notifications, manifest cleanup. **Land together: the first v2 APK is the one that reads the old data.** | Importer round trip on a v6 fixture twice; Robolectric worker test; `LiveEqualsBacktestTest` rewritten; on the Fold7: backup, install over 1.7.0, import counts match, one scan |
| M6 | **Mockup** (HTML, phone + Fold7 cover + inner, real-looking data; done 2026-10-16) -> approval -> Today, Ledger, Settings; then Strategies, Markets; foldable panes. **Delete Signal Lab 1** in the same milestone: the old `ui/`, `state/`, `scan/` and analyst code, their tests, and the engine's `Signals.kt`, `Candlesticks.kt`, `LabPatterns.kt`, `LiveScan.kt`, `VariantLabels.kt` with the LEARNED exit and `endsTheDay` | Compose screen tests; `ui/` import-ban test; daily use on the phone |
| M7 | Remaining five built-ins, vetoes, custom-strategy DSL, `eval/` (PSR/DSR/MinTRL, bootstrap, CPCV/PBO, cost stress, tiers) | Statistics vs reference values frozen by a one-off script in `tools/`; DSL random-tree invariance |
| M8 | **Analyst agent**: `analyst/` package with the SDK client (or OkHttp fallback), Keystore key storage, the tool set above, tool-runner loop with streaming, chat screen, daily brief and weekly review as WorkManager jobs, cost meter and cap, "what was sent" view; share-sheet hand-off kept as fallback | Tool handlers unit-tested against the engine and ledger; the loop tested with a fake transport (recorded responses); one real run on the phone against the owner's key with the spend shown |
| M9 | Sync format 2 (+ reads format 1), updater port, CHANGELOG, tag `v2.0.0` | `SyncTest` adapted; `update/*` unchanged; GitHub **pre-release** until M5 was verified on device, because 1.7.0's updater offers the latest release automatically |
| M10 (2.1.0) | **PC analyst host** `analyst-host/` (built 2026-10-17, approved list): a JDK-only Kotlin program that runs the installed Claude Code headless (`claude -p`, restricted mode, only web search, web fetch and the bridge) on the owner's login; Signal Lab's tools reach Claude Code through a loopback MCP bridge and are run on the phone, so the PC keeps no copy of the data; HTTPS with a self-made certificate the phone pins, a pairing code on every request; on the phone the PC route is tried first, then the API key, then the Claude app | Host tested against a stand-in Claude Code replaying recorded output; one live tool round trip through real Claude Code; phone tested against a stand-in host and real TLS pinning; one real run from the phone still to do |

### Tests

Keep unchanged: `CandleClockTest`, `IndicatorsTest`, `ExitRulesTest`, `StatisticsTest`, most of
`RandomBaselineTest`, `WarningsTest`, CoinFilter half of `WatchlistRulesTest`, app `AndroidSafetyTest`,
`update/*`, `ClaudeHandoffTest`, `AnalystTextTest`. Adapt: `GoldenTest`, `RandomFingerprintTest`,
`ExitsTest` (frozen flags instead of `Signals`), `InvarianceTest` -> generic harness over
`Strategy.entries`, `LiveEqualsBacktestTest`, `GoldenPipelineTest`, `SyncTest`, `BackupTest`. Drop:
`CandlesticksTest`, `LabPatternsTest`, `LiveScanTest`, `ScannerTest`, `ScanControllerTest`,
`ServiceAndReceiverTest`, `AgedTradeTest`, `ChartsAndWarningsTest`, `PriceFeedTest`, list repository
tests, `LabFormatTest`, all `ui/` and most `state/` tests. New: invariance for every built-in and random
DSL trees; `simulate == resolve` per strategy exit; PSR/DSR/MinTRL reference values and properties
(PSR(0)=0.5, DSR monotone in N); block bootstrap preserves mean and lag-1 autocorrelation; CPCV purge;
importer round trip and idempotence; Room migration test; `ScanWorker` unique-work and no-duplicate-calls
on rerun; release-build check that Room's `*_Impl` survive R8.

### Risks noted up front

- AGP 9 built-in Kotlin with KSP is unproven here (M0 exists to prove it; fallback `android.builtInKotlin=false`).
- Samsung sleeps unused apps: exact daily alarm as primary trigger, periodic work as backstop, re-arm from
  the worker and boot; keep the battery-exemption prompt; no foreground service from boot.
- Derivatives data beyond 30 days is unrecoverable: it must be in backup and sync; `binance.us` has no
  futures, so gate on host.
- Cross-sectional strategies need daily candles for the whole offered universe (~400 pairs), not just top-N.
- Google sign-in for Drive is tied to package + signing SHA-1: unchanged key keeps it working.

## Verification

1. Engine: `./gradlew :engine:test` green at every milestone; the M2 backtest table on golden coins is
   sanity-read (trade counts, win rates, excess within plausible ranges).
2. App unit tests: `./gradlew :app:testDebugUnitTest` green; importer round trip proves counts equal the
   source tables.
3. Device (M5 and M8): back the phone up with the 1.7.0 backup-to-file first; `:app:assembleRelease`;
   `adb install -r` over 1.7.0 (never uninstall); confirm the import log counts, that the old alarm is
   cancelled (`dumpsys alarm | grep signallab`), that one daily scan runs and a call appears with a
   notification; fold and unfold on Today and Markets.
4. Mockup gate at M6: the owner approves the HTML mockup before Compose work starts.
5. Every code change still goes through the CLAUDE.md gate: the list of changes, then the literal "approve".

## Not in 2.0.0 (explicitly later)

Short-side strategies (needs perps paper model), hedged funding carry, Learn pages (replaced by the
evidence card and a short "how to read a call" page), CSV export beyond the ledger, Play Store listing,
and any sign-in with the consumer Claude login (not permitted by Anthropic's terms; the API key on the
plan's monthly credit is the supported route).
