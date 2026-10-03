# Changelog

Each release gets a section here; the release workflow (added with the updater) publishes it as the
release notes.

## Unreleased

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
