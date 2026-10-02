# Changelog

Each release gets a section here; the release workflow (added with the updater) publishes it as the
release notes.

## Unreleased

- Project set up: Gradle build, signing, CI, empty engine and app shell.
- Engine: indicators, all signals (trend state, Donchian, time-series and cross-sectional momentum,
  big-move fade, intraday momentum), paper-trade exits, random baselines, cluster statistics and
  verdicts, ported to Kotlin from the research version.
- Golden test data exported once from the research version: real candles plus the exact signals,
  trades and statistics it produced. The Kotlin engine reproduces all of them.
