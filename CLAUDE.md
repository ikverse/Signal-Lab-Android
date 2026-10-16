# Signal Lab Android

A native Kotlin and Compose app that gives daily calls from a small set of research-backed crypto strategies (Binance spot,
long-only, daily closes), each with its evidence, and keeps an honest paper ledger of whether each strategy beats random
entries after fees. Research tool, not financial advice: no real money is ever traded.

Signal Lab 2 (branch `v2`, plan in `docs/v2-plan.md`, evidence in `docs/strategy-research.md`) replaced Signal Lab 1's
screens and patterns; Signal Lab 1's record is imported once into the new ledger. The Python and Termux version
(`ikverse/Signal-Lab`) is retired; its one remaining job was exporting test data once (see `tools/`). Minimum Android 10.

## Working agreements

- **Never implement without approval.** State the change as a list and wait for the literal word
  "approve". "ok", "do it" and a refinement are not approval.
- **Standing approval, granted 2026-10-16: Signal Lab 2, milestones M2 to M9 of `docs/v2-plan.md`.**
  Build them back to back on branch `v2` without a new "approve" per change. Commit each milestone on
  `v2` once its tests pass; never push. Solve implementation problems without asking and log each
  decision in `docs/CHANGELOG-ai.md`. Still stop and ask for: (1) installing on the phone (first at M5;
  the user backs up 1.7.0 and connects the Fold7); (2) a change of product or scope the plan does not
  already decide; (3) any push or release tag; (4) the Claude API key at M8; (5) anything that deletes
  user data.
- **Build plus unit tests is the loop.** No emulator and no screenshots unless asked. Install on the
  connected phone and hand over.
- **Push and release are separate.** Push = commit and push `main`; CI runs the tests and nothing
  ships. Release = bump the version, add the `CHANGELOG.md` section, commit, tag `vX.Y.Z`, push the
  tag **explicitly** (`git push origin vX.Y.Z`; lightweight tags don't travel with `--follow-tags`).
- Filter Gradle output; raw build logs are the largest source of context growth.

## Commands

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :engine:test :app:assembleDebug 2>&1 | grep -E "^e:|^w:|FAILED|BUILD"
```

`local.properties` (gitignored) holds `sdk.dir` and the signing settings. **Write it with exactly
`C:\\Users\\...` (doubled backslashes)**: a single backslash is read as an escape and the build dies
with "Invalid file path".

```bash
export ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
timeout 60 "$ADB" -s <serial> install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

Wrap every adb call in `timeout N`.

## Layout

- `engine/` — plain Kotlin, **no Android**: candles, indicators, regime, random baselines, statistics (`core` package),
  `strategy/` (the `Strategy` interface and built-ins), `eval/` (fills, baseline, backtest, live rules). Its tests run on a
  PC in seconds. `allWarningsAsErrors` is on. Test helpers and golden data live in `src/testFixtures`.
- `app/` — `data/` (Binance, candles, universe, futures), `ledger/` (Room ledger, importer, preferences), `runner/` (daily
  pass, worker, alarm, notifications), `model/` (screen state), `view/` (Compose screens), `update/`. Depends on `:engine`.
- `tools/` — one-off scripts, not part of the build.

## The engine's rules

- A signal on bar *i* uses only bars up to *i*. Cutting history at *i* must give the same answer for bars up to *i* as the
  full history. Tested for every registered strategy.
- A call enters at the **next** candle's open. Each strategy has its own exit plan (a safety stop in ATR, its own exit
  signal filled at the next open, a time limit); the stop is checked first, and a gap fills at the open.
- Costs: 0.25% round trip for BTC and ETH, 0.40% for everything else, unless changed in Settings.
- Random baselines come from the same coin, ±90 days, same BTC regime, same exit plan. Their generator is counter-based
  (splitmix64), so a draw never depends on iteration order.
- Every strategy is registered before it runs; the count of everything ever tried is the multiple-testing denominator.

## Signing

The key is the update: Android refuses an update signed by a different key. It lives in GitHub secrets
(`SIGNAL_LAB_KEYSTORE_BASE64`, `SIGNAL_LAB_KEYSTORE_PASSWORD`, `SIGNAL_LAB_KEY_ALIAS`) and locally in
`local.properties` as `SIGNAL_LAB_KEYSTORE_FILE` and the same two names. **Back the keystore up
somewhere safe**; losing it means every install needs an uninstall.
