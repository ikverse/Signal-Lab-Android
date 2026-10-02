"""One-time export of test data for the short charts and the candlestick patterns.

Writes into engine/src/test/resources/golden/:
  candles_1m.csv.gz, candles_15m.csv.gz   real Binance candles for four coins, ending where the existing golden
                                          daily data ends, so the existing BTC daily candles give their market regime
  candlesticks_expected.json.gz           what TA-Lib (the library the research used) answers for CDLHARAMI and
                                          CDLHIKKAKE on every golden candle, for the Kotlin patterns to match

Run once, with TA-Lib installed (pip install TA-Lib). The Python is never used again: its answers are frozen
into the repo as tests.
"""
import csv
import gzip
import io
import json
import os
import time
import urllib.request

import numpy as np
import talib

HERE = os.path.dirname(os.path.abspath(__file__))
GOLDEN = os.path.join(HERE, "..", "engine", "src", "test", "resources", "golden")
COINS = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"]
DAY = 86_400_000


def get(url):
    for attempt in range(8):
        try:
            return json.loads(urllib.request.urlopen(url, timeout=30).read())
        except Exception:
            time.sleep(2 * (attempt + 1))
    raise SystemExit("Binance did not answer: " + url)


def read_golden(tf):
    rows = {}
    with gzip.open(os.path.join(GOLDEN, f"candles_{tf}.csv.gz"), "rt") as f:
        r = csv.reader(f)
        next(r)
        for sym, t, o, h, l, c, v, ct in r:
            rows.setdefault(sym, []).append((int(t), float(o), float(h), float(l), float(c), float(v), int(ct)))
    return rows


def fetch(symbol, interval, start, end):
    out, t = [], start
    step = {"1m": 60_000, "15m": 900_000}[interval]
    while t < end:
        page = get(f"https://api.binance.com/api/v3/klines?symbol={symbol}&interval={interval}&startTime={t}&limit=1000")
        if not page:
            break
        for k in page:
            if int(k[0]) < end:
                out.append((int(k[0]), k[1], k[2], k[3], k[4], k[5], int(k[6])))
        t = int(page[-1][0]) + step
        time.sleep(0.15)
    return out


def write_csv(name, rows_by_symbol):
    with gzip.open(os.path.join(GOLDEN, name), "wt", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["symbol", "open_time", "open", "high", "low", "close", "volume", "close_time"])
        for sym, rows in rows_by_symbol.items():
            for r in rows:
                w.writerow([sym, *r])


def main():
    d1 = read_golden("1d")
    end = (d1["BTCUSDT"][-1][6] + 1)  # the golden daily data ends here (a UTC midnight)
    print("golden ends at", end)
    for interval, days in (("1m", 7), ("15m", 60)):
        data = {s: fetch(s, interval, end - days * DAY, end) for s in COINS}
        for s, r in data.items():
            print(interval, s, len(r))
        write_csv(f"candles_{interval}.csv.gz", data)

    expected = {}
    for tf in ("1d", "4h", "1h", "15m", "1m"):
        rows = read_golden(tf)
        per_coin = {}
        for sym, r in rows.items():
            o, h, l, c = (np.array([x[i] for x in r], dtype=float) for i in (1, 2, 3, 4))
            harami = talib.CDLHARAMI(o, h, l, c)
            hikkake = talib.CDLHIKKAKE(o, h, l, c)
            per_coin[sym] = {
                "harami": [[int(i), int(harami[i])] for i in np.nonzero(harami)[0]],
                "hikkake": [[int(i), int(hikkake[i])] for i in np.nonzero(hikkake)[0]],
            }
        expected[tf] = per_coin
        print(tf, {s: (len(v["harami"]), len(v["hikkake"])) for s, v in list(per_coin.items())[:3]})
    with gzip.open(os.path.join(GOLDEN, "candlesticks_expected.json.gz"), "wt") as f:
        json.dump({"talib": talib.__version__, "series": expected}, f)


if __name__ == "__main__":
    main()
