# How the scorecard judges a pattern

A pattern that wins 55% of the time proves nothing on its own: in a rising market, entries at random times win about as often. So Signal Lab never asks "did it win?". It asks **"did it beat random entries, after costs?"**

## The comparison

For every pattern trade, Signal Lab draws {{RANDOM_DRAWS}} random entries and gives them exactly the same exit and costs. The random entries come from:

- **the same coin**,
- **close in time**: {{RANDOM_WINDOWS}} either side of the signal, depending on the chart size,
- **the same market mood**: Bitcoin above its {{REGIME_MA_DAYS}}-day average, or below it.

The row's **VS RANDOM** figure is the average of (the trade's result minus what its random entries did).

## What the columns mean

| Column | Meaning |
|---|---|
| Trades | Closed trades, and in brackets the ones still open |
| Win rate | Share of closed trades that finished above zero after costs |
| Average | Average result per closed trade, after costs |
| Vs random | How much better (or worse) than the random entries, on average |
| Verdict | See below |

## Verdicts

```mermaid
flowchart TD
  A[Closed trades] --> B{At least {{TIER_1}}?}
  B -- no --> N[No verdict]
  B -- yes --> C{Beats random strongly,<br/>after correcting for<br/>every pattern tried?}
  C -- yes --> E[Edge]
  C -- no --> D{Clearly worse<br/>than random?}
  D -- yes --> L[Losing]
  D -- no --> X[No edge]
```

- **No verdict**: fewer than {{TIER_1}} closed trades, or too little to measure.
- **Edge**: the result beats random entries by a margin that is very unlikely to be luck. In numbers: a statistic (t) of at least {{EDGE_T}}, and still under {{ALPHA_PCT}}% after the correction for how many patterns were tried.
- **Losing**: clearly worse than random (t at or below {{LOSING_T}}).
- **No edge**: enough trades, and neither of the above.

Trades in the same calendar week count as one piece of evidence. Ten coins breaking out on the same day are one market event, not ten.

## How firm a verdict is

By the number of closed trades behind it: under {{TIER_1}} **No verdict**, {{TIER_1}} to {{TIER_2}} **Early read**, {{TIER_2}} to {{TIER_3}} **Provisional**, {{TIER_3}} or more **Meaningful**.

## Why most rows say "No verdict" for a long time

Every pattern, on every chart size, is another test. Test enough and a few will look good by pure luck. So the bar for **Edge** rises with the number of patterns ever tried, shown at the bottom of the [Scorecard](go:scorecard). The honest outcome of a fair test is often "no edge", and that is useful to know.

## Treat an Edge as perishable

Published edges fade once people know about them. A pattern that earns an Edge now should be watched, not trusted forever.

> Research, not financial advice. No real money is ever traded.
