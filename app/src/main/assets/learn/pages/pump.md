# Warning: a pump in progress

**This is a warning, never a paper trade.**

## What it looks for

A coin that rose **{{PUMP_RISE_PCT}}% or more in {{PUMP_MINUTES}} minutes**, on **{{PUMP_VOLUME_MULTIPLE}} times** the volume it normally trades in such a stretch (measured over the previous {{PUMP_NORMAL_HOURS}} hours). Only 1-minute and 5-minute charts are fine enough to see one, so a coin needs one of those in your list.

## Why it is a warning

- A pump usually peaks about **70 seconds** after it starts, at roughly +25% (Gandal et al.). Late entries are left holding it.
- A year later such coins were down around 30% on average.
- Pumps can be spotted about 25 seconds in from a burst of market orders (La Morgia et al.), long before a candle-based alert can fire.

By the time a candle has closed and Signal Lab has scanned it, most of the move has happened. The alert says: **do not chase.**

## What Signal Lab does

It sends a warning (its own notification channel, which you can silence) and shows it in the coin's Details. It opens **no** paper trade, so a pump never appears in the scorecard.

> Research, not financial advice. No real money is ever traded.
