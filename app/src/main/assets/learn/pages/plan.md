# Profit goal and loss limit

> **In short:** Every practice trade has three prices. The entry price is where it starts. The profit goal is where it is closed with a gain. The loss limit is where it is closed to keep a loss small. If neither is reached, it is closed when its time runs out.

## The three prices

- **Entry price.** The open of the candle after the pattern showed up. That is the first price anyone could have had.
- **Profit goal.** Set above the entry price by a multiple of the coin's normal move (the average candle range over the last {{ATR_WINDOW}} candles), so it suits calm and wild coins alike.
- **Loss limit.** Set below the entry price in the same way. Some patterns use a trailing stop instead: the loss limit follows the price up as it rises and never moves down. A trade like that has no fixed profit goal.

## When both are touched in one candle

The app assumes the loss limit was reached first. That keeps results on the cautious side. If the price jumps past a level, the trade closes at the first price after the jump, not at the level.

## Why not a fixed percentage

A move of 2% is large for a calm coin and tiny for a wild one. Using each coin's own normal move keeps the plan fair across coins. See [What a practice trade is](learn:paper-trade) for how each kind of pattern ends.

> Research, not financial advice. No real money is ever traded.
