# Day momentum: a strong start to the day

> **In short:** If a coin starts the day strongly, it sometimes keeps rising into the end of the day.

**Evidence: some proof, for Bitcoin.** Signal Lab runs a simpler version, on every coin you watch.

## What it looks for

Each UTC day starts at 00:00. If the **first candle of the day rose**, and by enough to be in the top {{DAY_MOM_TOP_PCT}}% of the first candles of the last {{DAY_MOM_HISTORY_DAYS}} days, the pattern fires near the end of the day. The pretend trade enters one candle before midnight and is held for the **last candle** of the day.

```mermaid
flowchart LR
  A[First candle of the UTC day] --> B{Rose, and in the top {{DAY_MOM_TOP_PCT}}%<br/>of recent first candles?}
  B -- yes --> C[Enter one candle before midnight]
  C --> D[Hold the last candle of the day]
  B -- no --> E[No signal today]
```

## Where it runs

30-minute and 1-hour charts.

## How a paper trade ends

Held: a single candle, no target and no stop.

## What the research says

- For Bitcoin, the return in the first busy half-hour of the day predicted the return in the last half-hour, on data the model had not seen: out-of-sample R-squared of 1.09%, up to 16.7% a year (Shen, Urquhart and Wang, 2022).

## Honest limits

- That study used Bitcoin's own best half-hour. This version uses the first candle of the UTC day on whatever coin you watch, so it is a loose copy.
- Bitcoin's effect is small per day and was measured before today's trading hours shifted with the US Bitcoin funds.
- Trades are short, so costs matter.

> Research, not financial advice. No real money is ever traded.
