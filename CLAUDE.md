# Signal Lab Android

A native Kotlin and Compose app that watches the coins in the user's active watchlists, turns every
signal into a paper trade, and keeps an honest scorecard of whether each signal beats random entries
after fees. Research tool, not financial advice: no real money is ever traded.

It is the whole product. The earlier Python and Termux version (`ikverse/Signal-Lab`) is retired and
frozen; its one remaining job was exporting test data once (see `tools/`). Minimum Android 10 (API 29).

## Working agreements

- **Never implement without approval.** State the change as a list and wait for the literal word
  "approve". "ok", "do it" and a refinement are not approval.
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

- `engine/` — plain Kotlin, **no Android**: indicators, signals, exits, random baselines, statistics.
  Its tests run on a PC in seconds. `allWarningsAsErrors` is on.
- `app/` — Compose UI, services, storage, Drive backup, updater. Depends on `:engine`.
- `tools/` — one-off scripts, not part of the build.

## The engine's rules (carried over from the research)

- A signal on bar *i* uses only bars up to *i*. Cutting history at *i* must give the same answer for
  bars up to *i* as the full history. Tested.
- A paper trade enters at the **next** candle's open. Target 2×ATR, stop 1×ATR, time limit by
  timeframe; stop is assumed first when both are touched in one candle; a gap fills at the open.
- Costs: 0.25% round trip for BTC and ETH, 0.40% for everything else.
- Random baselines come from the same coin, ±90 days, same BTC regime, same exits. Their generator
  is counter-based (splitmix64), so a draw never depends on iteration order.
- A variant defined after seeing a backtest is **forward-only**: its backtest is shown, but only live
  paper trades can give it a verdict.

## Signing

The key is the update: Android refuses an update signed by a different key. It lives in GitHub secrets
(`SIGNAL_LAB_KEYSTORE_BASE64`, `SIGNAL_LAB_KEYSTORE_PASSWORD`, `SIGNAL_LAB_KEY_ALIAS`) and locally in
`local.properties` as `SIGNAL_LAB_KEYSTORE_FILE` and the same two names. **Back the keystore up
somewhere safe**; losing it means every install needs an uninstall.
