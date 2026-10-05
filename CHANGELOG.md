# Changelog

Each release gets a section here; the release workflow (added with the updater) publishes it as the
release notes.

## Unreleased

## 0.1.5 - 2026-10-06

- New: the bar along the bottom of a narrow phone scrolls sideways and shows every place, so "More" is gone. The place you are on is kept in view.
- New: Paper trades are grouped by pattern. Each pattern has a header with its open and closed counts and the average result after costs, with the newest trades first and the pattern that fired last at the top.

## 0.1.4 - 2026-10-05

- Fixed: a notification, or "Show on chart", for a coin that isn't in a switched-on list opened the chart of the first coin instead. It now opens the coin it was about, with a note that it isn't being watched.
- Notifications now open the right place. A closed trade opens that trade in Trades. A pump or volume warning opens the coin's Details. The "opened" and "closed" summaries open Trades. A Binance problem opens Settings or Alerts.
- Back now returns to the tab you came from, for example from a chart opened out of Trades. On a wide screen it leaves in one press.
- Trades, Alerts and a half-filled New list keep what you set when you switch tabs.
- Learn pages now link to the pages and settings they mention.
- Fixed: deleting one of several lists on a narrow phone left a blank screen.
- Fixed: the first few alerts on a new install could replace the scanning notification.

## 0.1.3 - 2026-10-05

- New: Settings → Screen → "Side bar on the right". When the phone is held sideways, or the screen is wide, the side bar moves to the right edge.
- Learn opens faster. The page text shows right away, and the diagram library loads only for the pages that have a diagram.
- The chart and Learn pages are now kept between visits instead of being rebuilt every time you open them.

## 0.1.2 - 2026-10-04

- Fixed: the first list you make now keeps its coins and is switched on. Before, it could be left empty and off, and coins could not be added to it.
- Fixed: "Add coins" on an existing list now shows the coins and adds them.
- Pick coins by Volume, Gainers, Losers (1 hour, 24 hours, 7 days), Most trades, Volatile or New listings (last 30 days).
- Chart: a new Indicators menu (Volume, moving averages, Bollinger Bands, RSI, MACD), a Draw menu (trend line, horizontal line, ray, parallel channel, Fibonacci) and a Latest button. The chart now resizes with its pane.
- Lists and Learn have the same resizable panes as Markets.
- Settings: "Keep the screen on and dim it".

## 0.1.1 - 2026-10-03

- Screens (M5): Markets (coin list, chart, details), Paper trades, Scorecard, Alerts, Learn (16 pages),
  Lists and Settings, with a first-run "Choose your coins". The layout follows the window width: a bottom
  bar on a narrow screen, a side rail with two or three panels on a wide one. The scorecard is built from
  your live paper trades and raises the bar for a verdict with every pattern tried.
- Charts you choose (M4b): each list picks its own chart sizes from 1 minute to 1 day. Added Bullish
  Harami and Hikkake, an intraday breakout and a 30-minute day momentum. Warnings for pumps, new coins and
  volume spikes. Exits chosen per pattern (trailing, learned or held). Binance's own 0.10% fee each way
  by default. History is kept per chart and trimmed daily.
- Background scanning (M4): scans each candle as it closes with the app closed, opens a paper trade for
  every signal it notices in time (otherwise logs it as missed), follows open trades to their exit, and
  sends alerts. Needs notifications, exact alarms and a battery exemption to be reliable.
- Updater (M6): Settings looks at this app's GitHub releases once a day (and on "Check now") and offers a
  newer version with its notes. Tapping Download and install fetches the file, checks it is complete, is this
  app, is newer, and is signed with the same key as the installed copy, then opens Android's installer,
  which still asks before it installs. A file that fails any check is deleted and never handed over. The
  release workflow builds, signs and publishes the file when a version tag is pushed.

- Data layer (M3): Binance client with pacing, rate-limit waits, retries and a clear message when
  Binance refuses the network; the closed-candle rule judged by Binance's clock; candle sync that
  resumes interrupted downloads and refills holes; a candle store and a record database (append-only
  trades and exits, enforced by SQL triggers); watchlists with the 30-per-list and 150-active rules;
  the pair list with stablecoin, wrapped and leveraged-token filtering; history downloads with progress.
- Debug-only readout on the placeholder screen, with a button that builds a test list.

- Project set up: Gradle build, signing, CI, empty engine and app shell.
- Engine: indicators, all signals (trend state, Donchian, time-series and cross-sectional momentum,
  big-move fade, intraday momentum), paper-trade exits, random baselines, cluster statistics and
  verdicts, ported to Kotlin from the research version.
- Golden test data exported once from the research version: real candles plus the exact signals,
  trades and statistics it produced. The Kotlin engine reproduces all of them.
