# Intraday breakout: leaving the usual range

> **In short:** The price has moved further from the day's open than it usually does, which can mean a bigger move is under way.

**Evidence: weak.** The reported result is before fees.

## What it looks for

At each time of day, Signal Lab works out how far the price usually strays from the day's open (in either direction), averaged over the last {{BREAKOUT_DAYS}} days. A signal is the first candle of a run to close **above the open plus that usual stray**.

<div class="figure"><svg viewBox="0 0 320 120" width="320" height="120" xmlns="http://www.w3.org/2000/svg">
<line x1="10" y1="64" x2="310" y2="64" stroke="#868993" stroke-width="1"/>
<text x="12" y="78" fill="#868993" font-size="11">day's open</text>
<path d="M10,52 Q80,44 150,50 T310,38" fill="none" stroke="#868993" stroke-width="1" stroke-dasharray="4 3"/>
<text x="12" y="46" fill="#868993" font-size="11">open plus the usual stray</text>
<polyline points="10,64 60,68 110,58 160,60 200,48 240,34 290,28" fill="none" stroke="#d1d4dc" stroke-width="2"/>
<circle cx="240" cy="34" r="5" fill="#089981"/>
<text x="310" y="108" fill="#868993" font-size="11" text-anchor="end">closes above the range: signal</text>
</svg></div>

It needs the previous {{BREAKOUT_DAYS}} days to have the same time of day, so a gap in the data means no signal, never a wrong one. The last candle of a day is never a signal.

## Where it runs

15-minute, 30-minute and 1-hour charts.

## How a paper trade ends

Trailing stop, and always closed at the end of the UTC day.

## What the research says

- On Bitcoin from 2018 to 2025, a "noise zone" breakout showed a risk-adjusted return near 1.6, **before fees** (Concretum, a practitioner study, not peer reviewed).
- The same source found trends work best from Sunday 23:00 UTC for about a day.

## Honest limits

- Before fees only, one coin, one source.
- Opening-range breakouts in crypto have no fair, controlled test.
- Signal Lab applies it to every coin you watch, which that study did not.

> Research, not financial advice. No real money is ever traded.
