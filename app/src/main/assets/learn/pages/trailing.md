# How a lab trade ends

> **In short:** An idea chooses how its practice trades end: a trailing stop that follows the price up, a goal and time limit learned from earlier matches, or a fixed number of candles.

## Trailing stop

The loss limit follows the highest price reached and never moves down. A trade ends when the price falls back to it. This lets a long rise run, and gives back part of it at the end. It has no fixed profit goal.

## Learned goal

The app looks at what the same idea did after its earlier matches, then uses the typical best rise as the profit goal and the typical time it took as the time limit. With too few earlier matches it simply holds for a fixed time. See [Profit goal and loss limit](learn:plan).

## After N candles

The trade is held for a fixed number of candles, from 1 up to {{LAB_MAX_HOLD}}, then closed at whatever the price is. There is no profit goal and no loss limit. It is the simplest to reason about, and it shows what the idea itself does with no exit tricks.

## Which to choose

There is no best answer. A fixed hold is the easiest to compare with other ideas.

> Research, not financial advice. No real money is ever traded.
