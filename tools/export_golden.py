"""One-time export of golden test data from the finished Python engine.

Run with the Python project's own interpreter, from anywhere:

    <signal-lab>\\.venv\\Scripts\\python tools\\export_golden.py

It reads the Python project's database and imports its modules, and changes nothing there. The files
it writes are frozen into engine/src/test/resources/golden/ as the Kotlin engine's regression tests.
After this the Python code is never run again.

What is exported, and what is deliberately not:
  * Candles: real Binance data, exactly as stored, so Kotlin sees the same doubles.
  * Indicator arrays, signal flags, paper trades (entry, exit, fees, horizons) and their
    deterministic statistics, which Kotlin must reproduce exactly.
  * Fixed-input vectors for the statistics functions (cluster t, Holm, Benjamini-Hochberg, tiers).
  * NOT the random baselines or anything computed from them (excess, t of the excess): those come
    from numpy's generator, which Kotlin does not reproduce. Kotlin has its own counter-based
    generator and is checked statistically instead.
"""

import gzip
import json
import math
import sqlite3
import sys
from pathlib import Path

import numpy as np

PY_ROOT = Path(r"C:\Users\kabas\Documents\Claude\Claude Code\signal-lab")
OUT = Path(__file__).resolve().parent.parent / "engine" / "src" / "test" / "resources" / "golden"
sys.path.insert(0, str(PY_ROOT))

import config            # noqa: E402
import indicators as ind  # noqa: E402
import scorecard         # noqa: E402
import store             # noqa: E402
from backtest import compute_signals  # noqa: E402

DAY_MS = 86_400_000
COINS_1D = ["BTCUSDT", "ETHUSDT", "BNBUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT", "LTCUSDT",
            "TRXUSDT", "SOLUSDT", "XLMUSDT", "HBARUSDT"]
COINS_4H = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"]
COINS_1H = ["BTCUSDT", "ETHUSDT", "DOGEUSDT"]
DAYS = {"1d": None, "4h": 730, "1h": 400}


def clean(x):
    """JSON-safe: NaN and infinities become null; numpy scalars and arrays become plain Python."""
    if isinstance(x, np.ndarray):
        return [clean(v) for v in x.tolist()]
    if isinstance(x, (np.floating, float)):
        return None if (math.isnan(x) or math.isinf(x)) else float(x)
    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.bool_,)):
        return bool(x)
    if isinstance(x, dict):
        return {str(k): clean(v) for k, v in x.items()}
    if isinstance(x, (list, tuple, set)):
        return [clean(v) for v in (sorted(x) if isinstance(x, set) else x)]
    return x


def write_json_gz(path, obj):
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
        json.dump(clean(obj), f, separators=(",", ":"))


def write_candles(path, panel):
    with gzip.open(path, "wt", encoding="utf-8", compresslevel=9) as f:
        f.write("symbol,open_time,open,high,low,close,volume,close_time\n")
        for sym, c in panel.items():
            for i in range(len(c["t"])):
                f.write(f"{sym},{int(c['t'][i])},{float(c['open'][i])!r},{float(c['high'][i])!r},"
                        f"{float(c['low'][i])!r},{float(c['close'][i])!r},{float(c['volume'][i])!r},"
                        f"{int(c['close_time'][i])}\n")


def load_panel(conn, coins, tf, days):
    panel = {}
    for sym in coins:
        c = store.load_candles(conn, sym, tf, 0)
        if c is None:
            raise SystemExit(f"no {tf} candles for {sym}")
        if days is not None:
            keep = c["t"] >= c["t"][-1] - days * DAY_MS
            c = {k: (v[keep] if isinstance(v, np.ndarray) else v) for k, v in c.items()}
        panel[sym] = c
    return panel


def trade_row(t):
    return [t["symbol"], t["bar_time"], t["entry_time"], t["entry_price"], t["exit_time"], t["exit_price"],
            t["exit_reason"], t["bars_held"], t["gross"], t["net"], t["regime"],
            {h: v["net"] for h, v in t["horizons"].items()}]


def deterministic_stats(trades):
    if not trades:
        return {"n": 0}
    nets = np.array([t["net"] for t in trades])
    wins, losses = nets[nets > 0].sum(), -nets[nets < 0].sum()
    return {
        "n": len(nets), "hit": float((nets > 0).mean()), "mean": float(nets.mean()),
        "median": float(np.median(nets)), "profit_factor": float(wins / losses) if losses > 0 else None,
        "exits": {r: sum(1 for t in trades if t["exit_reason"] == r) for r in ("target", "stop", "time")},
        "avg_bars_held": float(np.mean([t["bars_held"] for t in trades])),
        "gross_mean": float(np.mean([t["gross"] for t in trades])),
    }


def stats_vectors():
    """Fixed inputs for the statistics functions, so Kotlin can be held to them exactly."""
    rng = np.random.default_rng(12345)
    cases = []
    for n, g in ((60, 12), (200, 40), (31, 31), (500, 7)):
        x = rng.normal(0.002, 0.02, n)
        clusters = rng.integers(0, g, n)
        cases.append({"x": x, "clusters": clusters, "cluster_t": scorecard.cluster_t(x, clusters)})
    # a degenerate case: one cluster only must give NaN
    cases.append({"x": rng.normal(0, 1, 10), "clusters": np.zeros(10, dtype=int), "cluster_t": float("nan")})
    judged = []
    for trials in (6, 38, 38):
        scores = []
        for i in range(6):
            n = int(rng.integers(5, 400))
            t = float(rng.normal(0.5, 2.0))
            p = 0.5 * math.erfc(t / math.sqrt(2))
            scores.append({"variant": f"v{i}", "n": n, "t_cluster": t, "p": p})
        scores.append({"variant": "forward", "n": 120, "t_cluster": 3.4, "p": 0.0003})
        scores.append({"variant": "empty", "n": 0, "t_cluster": None, "p": None})
        inp = [dict(s) for s in scores]
        for s in inp:
            if s["t_cluster"] is None:
                s["t_cluster"] = float("nan")
        out = scorecard.correct_and_judge(inp, trials)
        judged.append({"trials": trials, "scores": scores, "p_holm": [s["p_holm"] for s in out],
                       "q_bh": [s["q_bh"] for s in out], "verdict": [s["verdict"] for s in out]})
    tiers = {str(n): scorecard.tier(n) for n in (0, 1, 29, 30, 31, 99, 100, 299, 300, 301, 5000)}
    return {"cluster_t": cases, "judged": judged, "tiers": tiers}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(f"file:{config.DB_PATH}?mode=ro", uri=True)
    panels = {tf: load_panel(conn, coins, tf, DAYS[tf])
              for tf, coins in (("1d", COINS_1D), ("4h", COINS_4H), ("1h", COINS_1H))}
    btc_daily = panels["1d"]["BTCUSDT"]
    run_id = conn.execute("SELECT MAX(run_id) FROM scores").fetchone()[0]
    for tf, panel in panels.items():
        write_candles(OUT / f"candles_{tf}.csv.gz", panel)

    # Indicators on one real series: BTC daily.
    c = btc_daily
    close, high, low = c["close"], c["high"], c["low"]
    ma20 = ind.sma(close, 20)
    indicators = {
        "input": "BTCUSDT 1d, full history, from candles_1d.csv.gz",
        "sma20": ma20, "sma50": ind.sma(close, 50), "sma200": ind.sma(close, 200),
        "rolling_max_prior_20": ind.rolling_max_prior(high, 20),
        "rolling_std_30_of_returns": ind.rolling_std(ind.pct_return(close, 1), 30),
        "true_range": ind.true_range(high, low, close), "atr14": ind.atr(high, low, close, 14),
        "pct_return_14": ind.pct_return(close, 14), "rsi14": ind.rsi(close, 14),
        "crossed_above_close_sma20": ind.crossed_above(close, ma20),
        "regime": scorecard.regime_series(btc_daily, c["close_time"]),
    }

    signals, trades, stats = {}, {}, {}
    rng = np.random.default_rng(config.RANDOM_SEED)
    for tf, panel in panels.items():
        regimes = {s: scorecard.regime_series(btc_daily, cc["close_time"]) for s, cc in panel.items()}
        sigs = compute_signals(panel, tf)
        signals[tf], trades[tf], stats[tf] = {}, {}, {}
        for (name, family, frozen), flags in sorted(sigs.items()):
            params = dict(frozen)
            signals[tf][name] = {"family": family, "params": params,
                                 "flags": {s: np.flatnonzero(f).tolist() for s, f in flags.items()}}
            tr, _ = scorecard.run_variant(flags, panel, tf, params, regimes, rng)
            trades[tf][name] = [trade_row(t) for t in tr]
            stats[tf][name] = deterministic_stats(tr)
            print(f"  {tf} {name:<18} signals {sum(len(v) for v in signals[tf][name]['flags'].values()):>6}"
                  f"  trades {len(tr):>6}")

    expected = {
        "meta": {"source": "signal-lab 0.2.0 (Python), exported once", "numpy": np.__version__,
                 "backtest_run_in_db": run_id,
                 "note": "Random-baseline outputs are not exported; see tools/export_golden.py"},
        "config": {k: getattr(config, k) for k in (
            "COST_MAJORS", "COST_OTHERS", "TREND_MA_WINDOWS", "DONCHIAN_LOOKBACKS", "TS_MOMENTUM_WEEKS",
            "TS_MOMENTUM_TOP_QUANTILE", "TS_MOMENTUM_HISTORY_DAYS", "XS_MOMENTUM_WEEKS",
            "XS_MOMENTUM_TOP_FRACTION", "XS_MOMENTUM_MIN_COINS", "FADE_HOURS", "FADE_SIGMA", "FADE_VOL_DAYS",
            "INTRADAY_MOM_TOP_QUANTILE", "INTRADAY_MOM_HISTORY_DAYS", "ATR_WINDOW", "TARGET_ATR", "STOP_ATR",
            "TIME_LIMIT_BARS", "HORIZON_BARS", "REGIME_MA_DAYS", "EDGE_T", "LOSING_T", "ALPHA",
            "VERDICT_TIERS", "VERDICT_TOP", "FORWARD_ONLY_VARIANTS", "FORWARD_ONLY_VERDICT")},
        "majors": sorted(config.MAJORS),
        "trade_columns": ["symbol", "bar_time", "entry_time", "entry_price", "exit_time", "exit_price",
                          "exit_reason", "bars_held", "gross", "net", "regime", "horizon_nets"],
        "indicators": indicators, "signals": signals, "trades": trades, "stats": stats,
        "statistics": stats_vectors(),
    }
    write_json_gz(OUT / "expected.json.gz", expected)
    for p in sorted(OUT.iterdir()):
        print(f"{p.name:<24} {p.stat().st_size / 1024:>9.0f} KB")


if __name__ == "__main__":
    main()
