# Ranking: the strongest coins in your list

> **In short:** Once a week the strongest coins in your list are picked, on the idea that winners tend to keep winning for a while.

**Evidence: moderate in published studies, contested since.**

## What it looks for

Every Monday at 00:00 UTC, Signal Lab ranks the coins **in one list** by their rise over the last {{XS_WEEKS}} weeks (two versions). The top {{XS_TOP_PCT}}% each get a signal. A list needs at least {{XS_MIN_COINS}} coins trading that week, or the week is skipped.

```mermaid
flowchart LR
  A[Monday 00:00 UTC] --> B[Rise over the last weeks, for each coin in the list]
  B --> C[Rank them]
  C --> D[Top {{XS_TOP_PCT}}% get a signal]
```

Each list is its own universe: the same coin can rank first in a small list and last in a big one.

## Where it runs

1-hour, 4-hour and daily charts.

## How a paper trade ends

A trailing stop (see *What a paper trade is*).

## What the research says

- Sorting coins by their last three weeks' return earned about 4% a week in the studies, mostly in **large, liquid** coins (Liu, Tsyvinski and Wu).
- A 2025 update found 2-week momentum still positive after 2020.

## Honest limits

- A study that included delisted coins found no link between returns and 1-week momentum.
- Momentum was not evident among coins that survived in the top 100 from 2017 to 2024.
- A small list means a rough ranking: eight coins is the minimum, more is better.

> Research, not financial advice. No real money is ever traded.
