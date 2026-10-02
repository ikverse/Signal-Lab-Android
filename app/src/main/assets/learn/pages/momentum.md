# Momentum: a coin's own strong week

**Evidence: moderate, but old and fragile.**

## What it looks for

A coin whose rise over the last {{TSMOM_WEEKS}} weeks (three versions) is in **its own top {{TSMOM_QUANTILE_PCT}}%** of the past {{TSMOM_HISTORY_DAYS}} days. Only the first candle of each run counts, and the threshold is refreshed once a day at midnight UTC.

## Where it runs

The 4-hour and daily charts only. It needs a year of history to know what "strong" means for that coin.

## How a paper trade ends

A trailing stop (see *What a paper trade is*).

## What the research says

- A strong Bitcoin week (one standard deviation up) was followed by about +3.2% over the next week and +3.7% over the next two (Liu and Tsyvinski, data to 2018).
- Across coins, momentum measured over two weeks was still positive after 2020, about 2% a week, while 12 and 24 week momentum had died.
- Momentum turns around after about four to six weeks. That is why Signal Lab uses 1 to 4 weeks and not months.

## Honest limits

- The main study ends in 2018 and no fair test since 2021 was found.
- Studies that include coins which later disappeared find **no** 1-week momentum.
- Returns have very fat tails: a few huge rallies make the average look better than the typical trade.

> Research, not financial advice. No real money is ever traded.
