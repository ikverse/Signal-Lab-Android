# Breakout: close above the recent high

**Evidence: some proof for the daily and 4-hour version, weak for short charts.**

## What it looks for

A candle that closes above the highest high of the previous {{DONCHIAN_LOOKBACKS}} candles (four versions). Only the first candle of each run counts.

<div class="figure"><svg viewBox="0 0 320 120" width="320" height="120" xmlns="http://www.w3.org/2000/svg">
<line x1="10" y1="40" x2="310" y2="40" stroke="#868993" stroke-width="1" stroke-dasharray="4 3"/>
<text x="12" y="34" fill="#868993" font-size="11">highest high of the last N candles</text>
<g stroke-width="2">
<line x1="40" y1="60" x2="40" y2="92" stroke="#f23645"/><rect x="35" y="66" width="10" height="18" fill="#f23645"/>
<line x1="75" y1="52" x2="75" y2="86" stroke="#089981"/><rect x="70" y="58" width="10" height="20" fill="#089981"/>
<line x1="110" y1="46" x2="110" y2="80" stroke="#f23645"/><rect x="105" y="52" width="10" height="20" fill="#f23645"/>
<line x1="145" y1="50" x2="145" y2="84" stroke="#089981"/><rect x="140" y="56" width="10" height="18" fill="#089981"/>
<line x1="180" y1="42" x2="180" y2="76" stroke="#f23645"/><rect x="175" y="50" width="10" height="16" fill="#f23645"/>
<line x1="215" y1="40" x2="215" y2="74" stroke="#089981"/><rect x="210" y="46" width="10" height="20" fill="#089981"/>
<line x1="270" y1="14" x2="270" y2="50" stroke="#089981"/><rect x="265" y="20" width="10" height="22" fill="#089981"/>
</g>
<text x="214" y="108" fill="#868993" font-size="11">this candle closes above: signal</text>
</svg></div>

## Where it runs

Every chart size.

## How a paper trade ends

A trailing stop (see *What a paper trade is*).

## What the research says

- In a test of about 15,000 trading rules on daily data, channel breakouts were among the best kinds, and survived a correction for trying so many (Hudson and Urquhart).
- An average of nine breakout lengths on Bitcoin since 2015 reported a risk-adjusted return near 1.6 after fees, with a 19% worst fall (Zarattini et al., not peer reviewed). Averaging several lengths is sturdier than picking one.

## Honest limits

- The plain "close above the N-day high" rule was the **weakest** class in that same test. The good results needed a tight range to form first, which Signal Lab does not require.
- A rule that worked best in-sample lost money in the first half of 2018.
- On 1-minute charts breakouts did **worse** than chance (Corbet et al.).
- It pays in trending years and bleeds in flat ones.

> Research, not financial advice. No real money is ever traded.
