# Chart sizes

A chart size is how much time one candle covers: 1 minute, 5 minutes, 15 minutes, 30 minutes, 1 hour, 4 hours or 1 day. You choose which sizes each list is watched on.

**A chart size is not how long a trade lasts.** It is the size of the candles the pattern is looked for in. A trade can end in minutes or in days; each pattern's exit decides (see [What a paper trade is](learn:paper-trade)).

## Which patterns run where

| Pattern | Charts |
|---|---|
| Trend | every size |
| Breakout | every size |
| Bullish Harami | every size |
| Hikkake | every size |
| Momentum (1 to 4 weeks) | 4h and 1d |
| Ranking within your list | 1h, 4h and 1d |
| Drop fade | 1h |
| Day momentum | 30m and 1h |
| Intraday breakout | 15m, 30m and 1h |

Warnings: a pump is looked for on 1m and 5m charts, a volume spike on the daily chart.

## What each size costs

Faster charts mean more signals and quicker results, and more data and battery.

- **1m**: at most {{MAX_1M}} coins across your active lists.
- **5m**: at most {{MAX_5M}} coins.
- **15m and up**: only the overall limit of {{MAX_COINS_ACTIVE}} coins across active lists ({{MAX_COINS_LIST}} in one list).
- Charts under an hour are followed by a service that stays awake. Turn **Follow charts under an hour** off in [Settings](go:settings) to save battery; most of their signals are then missed.

## How much history is kept

Only what each chart needs: {{HISTORY_KEPT}}. Older candles are deleted once a day. Your trades and scores are never deleted.

## Which size should I use?

There is no proven best one. The research found real but small effects on short charts and better support on hours and days. Signal Lab keeps a scorecard for each pattern on each chart, so over time it shows which combinations hold up.

> Research, not financial advice. No real money is ever traded.
