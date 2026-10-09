# Change over N candles

> **In short:** How much the price moved over the last N candles. A value of 0.05 means up 5%. A value of -0.1 means down 10%.

The change is written as a fraction, not a percentage, because that is what the lab reads: 0.05 is 5%, 0.01 is 1%, and a fall is negative. It is measured from the close N candles ago to the latest close.

## How it is used in the lab

- **The change over 24 candles is below -0.05.** The price has fallen more than 5% in that time. People use this to look for a sharp drop.
- **The change over 24 candles is above 0.1.** The price has risen more than 10%: strong recent movement.

The same number means different things on different chart sizes. 24 candles on the 1-hour chart is a day. 24 candles on the 1-day chart is almost a month.

> Research, not financial advice. No real money is ever traded.
