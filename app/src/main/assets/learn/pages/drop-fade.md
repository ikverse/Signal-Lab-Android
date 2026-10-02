# Drop fade: a sharp fall that may bounce

**Evidence: weak.** An experiment, not a proven pattern.

## What it looks for

A fall over the last {{FADE_HOURS}} hours (three versions) that is bigger than {{FADE_SIGMA}} times that coin's usual hourly movement, measured over the previous {{FADE_VOL_DAYS}} days. The fall itself is not allowed to inflate its own yardstick. The pretend trade bets on a bounce.

<div class="figure"><svg viewBox="0 0 320 110" width="320" height="110" xmlns="http://www.w3.org/2000/svg">
<polyline points="10,40 50,42 90,38 130,44 160,80 190,70 230,62 280,56" fill="none" stroke="#d1d4dc" stroke-width="2"/>
<circle cx="160" cy="80" r="5" fill="#089981"/>
<text x="120" y="100" fill="#868993" font-size="11">sharp fall: signal when the candle closes</text>
</svg></div>

## Where it runs

The 1-hour chart only.

## How a paper trade ends

- **Fade 1h** (and 2h, 4h): the exit is **learned** from what this pattern did before (see *What a paper trade is*).
- **Fade 1h, held 24 candles**: the same entry, held for 24 candles with no target or stop. It was added after an early look at the data hinted at a 24-hour bounce, so a backtest cannot promote it. Only its live trades count towards its verdict.

## What the research says

- Bitcoin's hourly returns tend to reverse a little: a correlation of about -0.06 at one hour and -0.09 at two hours, stronger after big jumps (De Nicola, 2015 to 2018, before costs).
- Over longer stretches, falls in big coins tend to **continue**, not bounce (Caporale and Plastun). Weekly reversals are mostly in small, thin coins.

## Honest limits

- One study, old data, no costs. The effect per candle is small.
- On a thin coin, a sharp fall can be news and keep going.
- It is here to be tested, and the scorecard will say whether it holds.

> Research, not financial advice. No real money is ever traded.
