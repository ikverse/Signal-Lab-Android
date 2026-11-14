"""History for the whole-account test (research/, docs/strategy-research.md section 8). Public data, no key.

    python tools/download_history.py klines     daily candles of every USDT pair Binance lists, delisted ones too
    python tools/download_history.py funding    funding history of the coins in research/data/top12.txt

Files already downloaded are kept; delete research/data to start again.
"""
import json, os, sys, time, urllib.request

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "research", "data")
os.makedirs(os.path.join(OUT, "d1"), exist_ok=True)
os.makedirs(os.path.join(OUT, "funding"), exist_ok=True)
START = 1546300800000  # 2019-01-01
STABLE = {"USDC", "BUSD", "TUSD", "USDP", "PAX", "DAI", "FDUSD", "EUR", "EURI", "AEUR", "UST", "USTC", "SUSD", "USDS", "USDSB",
          "GBP", "AUD", "BKRW", "IDRT", "BIDR", "NGN", "RUB", "TRY", "UAH", "BRL", "ZAR", "PLN", "RON", "ARS", "JPY", "MXN", "COP", "CZK",
          "USD1", "USDE", "XUSD", "BFUSD", "PYUSD", "WBTC", "WBETH", "BETH", "PAXG", "VAI", "USDD", "RLUSD", "EURC", "TUSDB"}


def get(url):
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "research-script"}), timeout=30) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa
            code = getattr(e, "code", None)
            if code in (400, 404):
                return None
            time.sleep(2 + 5 * attempt)
    return None


def klines(sym):
    path = os.path.join(OUT, "d1", sym + ".csv")
    if os.path.exists(path):
        return
    rows, start = [], START
    while True:
        part = get(f"https://api.binance.com/api/v3/klines?symbol={sym}&interval=1d&startTime={start}&limit=1000")
        if not part:
            break
        rows += part
        if len(part) < 1000:
            break
        start = part[-1][0] + 86400000
    now = time.time() * 1000
    rows = [r for r in rows if r[6] < now]  # closed candles only
    with open(path, "w") as f:
        for r in rows:
            f.write(f"{r[0]},{r[1]},{r[2]},{r[3]},{r[4]},{r[5]},{r[6]},{r[7]}\n")


def funding(sym):
    path = os.path.join(OUT, "funding", sym + ".csv")
    if os.path.exists(path):
        return
    rows, start = [], START
    while True:
        part = get(f"https://fapi.binance.com/fapi/v1/fundingRate?symbol={sym}&startTime={start}&limit=1000")
        if not part:
            break
        rows += part
        if len(part) < 1000:
            break
        start = part[-1]["fundingTime"] + 1
        time.sleep(0.25)
    with open(path, "w") as f:
        for r in rows:
            f.write(f"{r['fundingTime']},{r['fundingRate']}\n")


if sys.argv[1] == "klines":
    info = get("https://api.binance.com/api/v3/exchangeInfo")
    syms = []
    for s in info["symbols"]:
        b = s["baseAsset"]
        if s["quoteAsset"] != "USDT" or b in STABLE:
            continue
        if b.endswith(("UP", "DOWN", "BULL", "BEAR")) and len(b) > 4:
            continue
        syms.append(s["symbol"])
    print(len(syms), "pairs", flush=True)
    for i, s in enumerate(syms):
        klines(s)
        if i % 50 == 0:
            print(i, s, flush=True)
    print("done", flush=True)
else:
    for s in open(os.path.join(OUT, "top12.txt")).read().split():
        funding(s)
        print(s, flush=True)
