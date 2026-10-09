# Highest and lowest of the last N candles

> **In short:** The top (or bottom) price reached in the candles before this one. A price that closes above the highest is breaking out of its range.

"The highest price of the last 20 candles" is the highest high among the 20 candles before the current one. The current candle is not included, so the level cannot move with the candle being tested. "The lowest price of the last 20 candles" works the same way for the lowest low.

## How it is used in the lab

- **The price crosses above the highest of the last 20 candles.** A breakout: the price has just passed everything it reached recently.
- **The price is below the lowest of the last 20 candles.** The coin is at a new low for that stretch.

A longer length means a bigger range to break out of, and fewer matches. Many of the built-in patterns are variations on this idea.

> Research, not financial advice. No real money is ever traded.
