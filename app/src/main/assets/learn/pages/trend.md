# Trend: a coin starts rising above its average

> **In short:** The price has just moved above its recent average after being below it, which sometimes marks the start of a rise.

**Evidence: moderate for the idea, untested for this exact trigger.**

## What it looks for

The average closing price of the last {{TREND_WINDOWS}} candles (two versions, one per length). The signal fires on the first candle that closes above that average after being below it.

<div class="figure"><svg viewBox="0 0 320 110" width="320" height="110" xmlns="http://www.w3.org/2000/svg">
<polyline points="10,70 50,76 90,84 130,78 170,66 210,52 250,40 290,30" fill="none" stroke="#d1d4dc" stroke-width="2"/>
<polyline points="10,64 50,68 90,72 130,74 170,72 210,66 250,58 290,50" fill="none" stroke="#2962ff" stroke-width="1.5" stroke-dasharray="4 3"/>
<circle cx="170" cy="66" r="5" fill="#089981"/>
<text x="310" y="94" fill="#868993" font-size="11" text-anchor="end">first close above the average</text>
<text x="12" y="104" fill="#868993" font-size="11">white: price   blue dashed: average</text>
</svg></div>

## Where it runs

Every chart size.

## How a paper trade ends

A trailing stop (see *What a paper trade is*).

## What the research says

- Holding a coin only while it is **above** its 20 to 50 day average cut Bitcoin's worst loss from about 90% to about 65% and raised its return per unit of risk, over 2010 to 2018 (Detzel et al.).
- Across the ten biggest coins other than Bitcoin, 2016 to 2018, staying in only while above the 20-day average beat just holding by 8.76% a year, before costs (Grobys et al.).
- Coins above their 20-day average beat those below it in a cross-section of 3,244 coins, about 3.1% a week (Fieberg et al.). The 100 to 200 day versions showed nothing.

## Honest limits

- The research supports being **above** the average (a state). Signal Lab signals the **moment of crossing**, which no study tested. The [scorecard](go:scorecard) will tell.
- Rules picked on 2017 to 2021 data failed in 2022 to 2023.
- Much of the benefit is avoiding crashes, not picking winners.

> Research, not financial advice. No real money is ever traded.
