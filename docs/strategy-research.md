# Signal Lab 2: strategy research

What the published evidence says about predictable price patterns in crypto, as of October 2026, and what
Signal Lab 2 builds on it. Every built-in strategy points back to a section here.

Compiled on 2026-10-16 from three research passes: academic return anomalies; derivatives and flow
signals; technical rules, exits and evaluation method. Several papers were paywalled. Where only an
abstract or a summary was readable, the figure is marked **abstract-level**. A claim that rests on one
paper or one backtest is marked **single source**.

## How to read this

- **The fee frame decides most of it.** A retail Binance spot taker pays about 0.20% round trip, or
  0.15% with BNB fees. Spread and slippage add little on BTC and ETH, 0.1 to 0.25% on top-50 coins, and
  0.5 to 1% or more below that. A strategy has to clear roughly 0.25% per trade on majors and 0.40% on
  other coins before it earns anything.
- **Most academic results are gross and long-short.** Their short legs are usually micro-caps that
  cannot be shorted in practice. Signal Lab is long-only spot, so only the long side counts.
- **Most samples end before 2022.** Several effects have shrunk since, and the post-2022 tests are mostly
  working papers or practitioner backtests.

## 1. What survives

### 1.1 Daily trend and breakout rules

- **Trading-range breakout.** Go long above the highest high of the prior 50 to 200 days and leave below the
  lowest low. On daily BTC from 2010 to 2019 this had the clearest forecasting power of seven trend
  indicators and beat buy-and-hold on bootstrapped Sharpe ratios, in normal and boom periods alike.
  Gerritsen, Bouri, Ramezanifar and Roubaud, *Finance Research Letters* 34, 2020
  ([link](https://research-portal.uu.nl/en/publications/the-profitability-of-technical-trading-rules-in-the-bitcoin-marke/)).
  Abstract-level; the costs are not stated in the abstract.
- **The most important caveat.** Hudson and Urquhart tested about 15,000 rules in five families on BTC,
  ETH, LTC and XRP with corrections for data snooping. Every family was profitable in-sample, with breakeven
  costs well above market costs. Out of sample, predictability **disappeared for Bitcoin but persisted for
  the altcoins**. *Annals of Operations Research* 297, 2021
  ([link](https://ideas.repec.org/a/spr/annopr/v297y2021i1d10.1007_s10479-019-03357-1.html)).
- **Price above a moving average.** Long when the daily close is above its 5 to 100-day SMA, flat
  otherwise. This forecast BTC returns in and out of sample. In the 2010 to 2017 data, Sharpe rose from 2.0
  to 2.2 to 2.6 and the maximum drawdown fell from −89% to about −68%, with breakeven costs of 1.1 to 4.5% per
  switch. The gain comes mostly from avoiding drawdowns, not from higher returns. Detzel, Liu, Strauss, Zhou
  and Zhu, *Financial Management* 50, 2021 ([link](https://ideas.repec.org/a/bla/finmgt/v50y2021i1p107-137.html);
  [replication summary](https://www.cxoadvisory.com/currency-trading/timing-bitcoin-with-smas/)).
  A practitioner test of a 50-day rule on BTC from 2012 to 2023 found Sharpe 1.9 against 1.3 for
  buy-and-hold, gross ([Grayscale](https://research.grayscale.com/reports/the-trend-is-your-friend-managing-bitcoins-volatility-with-momentum-signals)).
- **A 20-day variable moving average on 11 large coins** earned 8.76% a year more than the market average
  excluding BTC, over 2016 to 2018. Grobys, Ahmed and Sapkota, *Finance Research Letters* 32, 2020
  ([link](https://osuva.uwasa.fi/handle/11111/619)). Single source, short sample.

**Used by:** Breakout (strategy 1) and Trend timer (strategy 2).

### 1.2 Time-series momentum at one to four weeks

- **A coin's own last one to two weeks predicts its next one to four weeks.** BTC's next week averaged
  11.2% after top-quintile weeks and 2.6% after bottom-quintile weeks over 2011 to 2018, with similar
  results for ETH and XRP. Liu and Tsyvinski, *Review of Financial Studies* 34, 2021
  ([NBER w24877](https://www.nber.org/papers/w24877)). These magnitudes come from a bubble-era sample and
  are not forward estimates.
- **It survives realistic frictions but not bear markets.** At 15 basis points per trade, time-series
  momentum is strong while cross-sectional momentum is weak. The effect concentrates in large winners and is
  "almost non-existent in bearish markets". Han, Kang and Ryu, SSRN 4675565, 2023
  ([link](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=4675565)). Working paper; exact lookbacks
  unconfirmed.
- **The horizon matters.** Momentum appears only when both the formation and the holding period are two
  to four weeks or less. A hold of four weeks or more loses, and a long hold after a short formation reverses
  strongly. Dobrynskaya, *Journal of Alternative Investments*, 2024
  ([summary](https://www.hse.ru/en/news/research/559533243.html)). Monthly specifications miss it entirely,
  which is why Grobys and Sapkota found almost none (*Economics Letters* 180, 2019,
  [link](https://osuva.uwasa.fi/handle/11111/2278)).
- **Momentum crashes.** Crypto momentum has severe crashes, and its significance can hinge on one coin.
  Volatility scaling reduces the crashes. Grobys and others, *Financial Markets and Portfolio Management*,
  2025 ([link](https://link.springer.com/article/10.1007/s11408-025-00474-9)). A survivor-bias-corrected
  study over 2017 to 2024 finds profitability "highly sample-dependent"
  ([link](https://osuva.uwasa.fi/handle/11111/20040)).

**Used by:** Momentum 2w (strategy 3), gated to bull markets.

### 1.3 Cross-sectional momentum, long-only, in liquid coins

- **Ranking coins by their last two to three weeks** and going long the top fifth was the strongest factor
  in Liu, Tsyvinski and Wu. Weekly long-short spreads were 2.7% at one week, 3.3% at two weeks, 4.1% at three
  weeks and 2.5% at four weeks, gross, over 2014 to 2018. *Journal of Finance* 77, 2022
  ([NBER w25882](https://www.nber.org/papers/w25882.pdf)). The median coin in that sample was worth $8M.
- **The economics are weaker than the statistics.** Momentum "prevails in larger cryptocurrencies but
  incurs substantial trading costs and extracts alphas largely from short positions", and anomaly returns
  concentrate in bull markets and fade over time. Fieberg, Liedtke and Zaremba, *International Review of
  Financial Analysis* 94, 2024 ([link](https://open.icm.edu.pl/handle/123456789/25544)).
- **The post-2022 evidence is negative after costs.** An informal out-of-sample backtest on 25 liquid Binance
  coins, at 20 bp each way, found net Sharpe 0.42 for 2020 to 2022, −0.48 for 2023 to 2024 and −2.18 for 2025 to
  2026, as cross-sectional dispersion fell about 38%
  ([GitHub](https://github.com/prams2104/crypto-momentum-backtest)). Single source, not peer-reviewed.
- **A combined trend factor** of several price-to-moving-average ratios has a breakeven cost of 1.41% per
  round trip with 68% weekly turnover, and it works in big, liquid coins. *Journal of Financial and
  Quantitative Analysis*, 2024
  ([link](https://www.cambridge.org/core/journals/journal-of-financial-and-quantitative-analysis/article/trend-factor-for-the-cross-section-of-cryptocurrency-returns/4C1509ACBA33D5DCAF0AC24379148178)).
  Abstract-level.

**Used by:** Leaders (strategy 4), tier Watch because of the decay.

### 1.4 Size decides whether a move continues or reverses

- **Large, liquid coins keep going and small, illiquid ones revert.** For large coins, the past week's return
  predicts continuation (t = +2.33), and closeness to the one-week high predicts it more strongly (t = +4.93).
  For small coins both reverse, with t = −7.31 and −9.03. Fičura, working paper 5:003, 2023
  ([link](https://wp.ffu.vse.cz/artkey/wps-202301-0003_impact-of-size-and-volume-on-cryptocurrency-momentum-and-reversal.php)).
  Single source, no costs.
- **The largest coins show daily momentum rather than reversal.** Across more than 3,600 coins, daily
  reversal is driven by illiquidity. Zaremba and others, *International Review of Financial Analysis* 78,
  2021 ([link](https://ideas.repec.org/a/eee/finana/v78y2021ics1057521921002349.html)).

**Used by:** Near high (strategy 5), tier Testing.

### 1.5 Low-volume reversal

- **A loser on low volume rebounds; a loser on high volume does not.** Reversal profits at daily to monthly
  horizons concentrate in low-volume, small, volatile pairs and are robust to linear costs over 2017 to 2022
  across several exchanges. Bianchi, Babiak and Dickerson, *Journal of Banking and Finance* 142, 2022
  ([working paper](https://www.riksbank.se/globalassets/media/rapporter/working-papers/2022/no.-413-trading-volume-and-liquidity-provision-in-cryptocurreny-markets.pdf));
  Kozlowski, Puleo and Zhou, *Applied Economics Letters* 28, 2021
  ([link](https://ideas.repec.org/a/taf/apeclt/v28y2021i11p887-893.html)).

**Used by:** Quiet dip (strategy 6), tier Testing, with a time stop and no price stop, because stops lower
the expected return of a mean-reverting entry.

### 1.6 Higher-timeframe filter on an hourly trend entry

- **One crypto test, gross only.** An hourly MACD rule on BTC from 2018 to 2025 made 4.6% a year at Sharpe
  0.33 over 2,262 trades. Requiring the daily trend to agree halved the trades and raised Sharpe to 0.80;
  an exit on the first hourly close below the open raised it to 1.07. Quantpedia, 2025
  ([link](https://quantpedia.com/how-to-design-a-simple-multi-timeframe-trend-strategy-on-bitcoin/)).
  Single source. At 0.15% per round trip the unfiltered version is negative after fees, and the filtered
  one still pays about 21% a year in fees against 6.6% gross.

**Used by:** Hourly trend (strategy 7), tier Testing and flagged as fee-fragile.

### 1.7 Market regime

- **Anomalies pay in bull markets.** Time-series momentum dies in bear markets (Han, Kang and Ryu), and
  anomaly returns concentrate in bull markets (Fieberg and others, 2024). No peer-reviewed crypto paper
  tests a "BTC above its 200-day average" gate directly. Practitioner work finds the 200-day rule
  useful as drawdown insurance, not as alpha
  ([Longtrend](https://thelongtrend.substack.com/p/i-tried-to-debunk-the-200-day-moving)).
- **Consequence.** The gate is applied where the evidence says the strategy dies in bear markets
  (strategies 3 to 5). Every call, gated or not, is tagged with the regime so the ledger can answer whether
  the gate helps.

### 1.8 Position sizing by volatility

- **Volatility scaling cuts tail risk everywhere.** It raises Sharpe for assets with a leverage effect.
  Harvey and others, *Journal of Portfolio Management*, 2018
  ([PDF](https://people.duke.edu/~charvey/Research/Published_Papers/P135_The_impact_of.pdf)); Moreira and
  Muir, *Journal of Finance*, 2017
  ([PDF](https://amoreira2.github.io/alan-moreira.github.io/VolPortfolios_published.pdf)). It reduces
  crypto momentum crashes (Grobys and others, 2025). No paper quantifies ATR-unit sizing in crypto.
- **Used by:** every strategy. One ATR(14) move equals 1% of the paper account, capped at 20% per call.

### 1.9 Exits

- **Stops only help where momentum exists.** Under a random walk a stop lowers the expected return.
  Kaminski and Lo, *Journal of Financial Markets*, 2014 ([link](https://dspace.mit.edu/handle/1721.1/114876)).
- **For trend entries, time stops and wide channel exits keep more of the edge than tight trailing stops.**
  On 105,708 US equity trades over 26 years, net of costs, give-back trailing stops did worst (−0.02 to
  −0.14% per trade), a Donchian channel exit made +0.24% and a time stop +0.52%
  ([Setup4alpha](https://setup4alpha.substack.com/p/trailing-stop-vs-fixed-stop-tested)). Single source,
  equities. In long trend trades, under 7% of trades produce the cumulative profit.
- **Used by:** channel or time exits on the trend strategies, time-only exits on the reversal strategy, and a
  wide safety stop of 3 ATR so a gap cannot wipe out a call. Each exit is shared with that strategy's random
  baseline, so the comparison stays fair.

### 1.10 Vetoes from flow and derivatives data

Derivatives and flow data are useful for **vetoes and context, not for entries**.

- **Pumps reverse fast.** Prices rise about 25% in the first 70 seconds and the reversal starts there. Most of the effect is
  gone within an hour, and the run-up starts in the five minutes before the pump. Li, Shin and Wang, *JFQA*
  ([summary](https://clsbluesky.law.columbia.edu/2019/01/07/cryptocurrency-pump-and-dump-schemes/));
  Xu and Livshits, USENIX Security 2019
  ([link](https://www.usenix.org/conference/usenixsecurity19/presentation/xu-jiahua)).
- **Abnormal volume predicts lower returns in crypto**, the opposite of equities. The effect disappears
  once a coin can be traded on margin. Garfinkel, Hsiao and Hu, *Financial Management*, 2025
  ([link](https://iro.uiowa.edu/esploro/outputs/workingPaper/The-Negative-Abnormal-Volume-Return-Relation/9984414059502771)).
  Abstract-level.
- **After a top-5% liquidation spike,** BTC averaged +0.78% over 30 days against +5.9% unconditionally, with
  a median of −1.1% and wide dispersion. K33 via [The Block](https://www.theblock.co/post/372115/whats-next-after-cryptos-fourth-largest-long-liquidation-event-of-2025).
  Single source, method unpublished. Binance's liquidation stream reports only the latest liquidation per
  symbol per second, so its totals undercount during cascades
  ([Binance docs](https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/All-Market-Liquidation-Order-Streams)).
- **High carry warns of crashes.** High futures basis and carry predict subsequent crashes. Schmeling,
  Schrimpf and Todorov, BIS working paper 1087 ([PDF](https://www.bis.org/publ/work1087.pdf)).

**Used by:** the vetoes on every entry, and the market line on Today.

## 2. What the evidence says to drop

| Dropped | Why | Source |
|---|---|---|
| Candlestick patterns, including the old harami and hikkake | 68 patterns on the top 23 coins: "of little use", and acting on them can lose money | [IEEE Big Data 2021](https://ieeexplore.ieee.org/document/9671826) |
| Any technical rule on 1m to 15m bars with taker fills | 5 and 15-minute frames do worse than 60 to 120-minute ones, and a BTC one-minute bar's range is below the fee | Cohen, *Review of Quantitative Finance and Accounting* 61, 2023 ([link](https://ideas.repec.org/a/kap/rqfnac/v61y2023i1d10.1007_s11156-023-01139-2.html)) |
| MACD or similar on 1h and faster, alone | 2,262 trades in seven years: negative after fees | Quantpedia 2025, above |
| Hour-of-day tilts, such as long BTC from 21:00 to 23:00 UTC | Hours chosen in-sample, weakened in 2022 and 2023, and two trades a day eat about 55% a year in fees | [Quantpedia](https://quantpedia.com/the-seasonality-of-bitcoin/) |
| Overnight versus US-session tilts | A real pattern before 2024 that has decayed since the ETFs | [Quantpedia](https://quantpedia.com/are-there-seasonal-intraday-or-overnight-anomalies-in-bitcoin/) |
| Fading big moves | After overreaction days, the contrarian strategy loses | Caporale and Plastun, *Journal of Economic Studies* 46, 2019 ([summary](https://quantpedia.com/price-overreactions-in-the-cryptocurrency-market/)) |
| RSI(2) | No crypto evidence, and intraday reports are negative | none found |
| Funding-rate extremes as entries | The fade's hit rate is 45 to 53%. Funding change at T explains next-period price with R² ≈ 0. Periods of high funding *outperform*. | [Presto Labs](https://www.prestolabs.io/research/can-funding-rate-predict-price-change); [backtest](https://tradingstrategies.work/blog/funding-rate-signal-btc-backtest) (single source) |
| Open interest by price quadrants, long/short ratio thresholds, "volume-confirmed" breakout multipliers | Vendor claims only, with no forward-return test anywhere | none found |
| BTC leads altcoins at the hourly scale | Predictive power near zero, and the cross-coin sign is negative where found | Khan, SSRN 7485359, 2026 (single source); Jia and others, *Journal of Empirical Finance* 74, 2023 ([link](https://ideas.repec.org/a/eee/empfin/v74y2023ics0927539823000956.html)) |
| 15-minute sign reversal | Real in about 90% of 183 Binance pairs, but 1.3 bp gross against a 5 bp cost | Kitron and Wengrowicz, [arXiv 2608.21888](https://arxiv.org/abs/2608.21888) |
| The learned exit | Its target is the median past best rise, so about half the trades hit it by construction | Signal Lab 1 audit, `docs/engine-audit.md` §4.4 |

The consequence is that Signal Lab 2 makes calls on daily, 4-hour and hourly closes only. Faster charts
can still be viewed.

## 3. Built-in strategies

| # | Strategy | Chart | Entry | Exit | Tier at launch | Evidence |
|---|---|---|---|---|---|---|
| 1 | Breakout | 1d | close above the highest high of the prior 50 days (also 20 on alts) | close below the prior 20-day low, or 60 days; safety stop 3 ATR | Proven on alts, Promising on BTC | §1.1 |
| 2 | Trend timer | 1d | close crosses above SMA(50); BTC and the top 10 | close below SMA(50) | Promising | §1.1 |
| 3 | Momentum 2w | 1d | 2-week return above 0 and above its 60-day median; the 30 most liquid coins; bull regime | 2 weeks; safety stop 3 ATR | Promising | §1.2 |
| 4 | Leaders | 1d, Monday close | top fifth of the universe by 3-week return; bull regime | 2 weeks | Watch | §1.3 |
| 5 | Near high | 1d | close within 1% of its 7-day high; the 30 most liquid coins; bull regime | 1 week | Testing | §1.4 |
| 6 | Quiet dip | 1d | 1-day return below −5% on volume below its 30-day median; volume ranks 10 to 80 | 5 days, no price stop | Testing | §1.5 |
| 7 | Hourly trend | 1h, filtered by 1d | hourly close crosses above EMA(50) while the daily SMA(50) is rising | hourly close below EMA(50), or 48 bars | Testing | §1.6 |

All strategies are long-only on Binance spot USDT pairs and enter at the next candle's open. The vetoes
in §1.10 apply to every entry.

## 4. How a strategy earns its tier

These are methods, not findings. They decide when Signal Lab is allowed to say a strategy works.

1. **Pre-registration.** Every strategy is registered before it is backtested, and the count of everything
   ever tried is the multiple-testing denominator. Harvey and Liu; López de Prado, *Advances in Financial
   Machine Learning*, chapter 12.
2. **Random baseline.** Each call is compared with random entries on the same coin within ±90 days, in the
   same regime, with the same exit. The excess gets a stationary block-bootstrap 95% interval from 2,000
   resamples. Politis and Romano 1994; Ledoit and Wolf, *Journal of Empirical Finance*, 2008
   ([PDF](https://uzh.ch/cmsssl/econ/dam/jcr:ffffffff-935a-b0d6-0000-00007214c2bc/jef_2008pdf.pdf)).
3. **Probabilistic and deflated Sharpe, and minimum track record length.** These are closed-form and cheap on a phone.
   The deflated Sharpe sets the bar at the best Sharpe expected by luck among N tries. The minimum track
   record length becomes "needs about K more calls". Bailey and López de Prado, *Journal of Portfolio
   Management*, 2014 ([SSRN](https://papers.ssrn.com/abstract=2460551);
   [formulas](https://portfoliooptimizer.io/blog/the-probabilistic-sharpe-ratio-bias-adjustment-confidence-intervals-hypothesis-testing-and-minimum-track-record-length/)).
   For a per-trade Sharpe of 0.1, the minimum is about 270 trades before allowing for fat tails, and
   400 to 600 with crypto's.
4. **Combinatorial purged cross-validation and the probability of backtest overfitting.** Custom
   strategies are tested over 8 blocks, giving 70 splits, and a probability above 0.3 is flagged. Bailey,
   Borwein, López de Prado and Zhu, 2014 ([overview](https://packages.oit.ncsu.edu/cran/web/packages/pbo/readme/README.html)).
5. **Cost stress.** Every number is shown at 1× and 2× the assumed cost. A strategy whose sign flips at 2×
   is marked fee-fragile.
6. **No verdict below 100 calls or below the minimum track record length.**

## 5. Costs used

| Coins | Round trip assumed | Basis |
|---|---|---|
| BTC, ETH | 0.25% | taker fees of 0.20%, or 0.15% with BNB, plus a small spread ([Binance fee schedule](https://www.binance.com/en/fee/schedule)) |
| Top 50 | 0.40% | plus 0.1 to 0.25% of spread and slippage |
| Ranks 50 to 300 | 0.75% | 0.5 to 1% observed; no public table by size bucket exists |

The user can change these in Settings; the stress test at 2× always runs.

## 6. Open questions the ledger will answer

- Does the BTC-regime gate improve live results, or only backtests?
- Does Breakout's edge on altcoins survive after 2022, when the published samples end?
- Does Leaders keep decaying? If its live excess turns negative, it is switched off.
- Do the vetoes help? Vetoed signals are still recorded, silently, so the answer can be measured.

## 7. The October 2026 pass: what crypto traders use, and what survives

On 2026-10-22 six more research passes looked at what active crypto traders use beyond price rules: derivatives and
positioning, on-chain and flows, event-driven plays, systematic quant work from 2023 to 2026, yield and carry, and real-money
execution. The synthesis is `docs/research-2026-10/report.md`; the six briefs beside it carry every source. Signal Lab 3
(`docs/v3-plan.md`) builds on this section. The owner decided the same day that the app keeps paper money and never places
orders; calls are copied by hand.

### 7.1 Sizing matters more than any new entry

- **Volatility scaling.** Out of sample on 150+ Binance perpetuals, January 2022 to December 2024, net of fees, slippage and
  funding: one-month time-series momentum at Sharpe 0.65 and a −34.8% maximum drawdown became Sharpe 1.83 and −16.1% when
  scaled to a 10% volatility target ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)). On daily BTC, ETH and SOL
  spot from 2017 to mid-2025: Sharpe 0.71 and −80% became 0.79 and −15%; "the trend signal added about 0.05 Sharpe"
  ([Summitward](https://summitward.com/learn/crypto-trend-following)). The gain is mostly drawdown, not return
  (Cederburg and others, [NBER w22208](https://www.nber.org/papers/22208)).
- **Binary in/out is the worst way to use a trend signal**; linear or nonlinear scaling beats it (Moskowitz and others, 2025,
  via [Marrazzo](https://antoniomarrazzo.substack.com/p/trend-following-mostly-a-volatility)).

**Used by:** the sizing engine (Signal Lab 2.3), which records both its size and the plain ATR size on every call, so the
ledger can say whether it helps on the owner's coins.

### 7.2 Funding as a regime, basis and heat as vetoes

- **Negative 30-day funding.** K33, May 2026: buying while the 30-day average BTC funding rate was negative, since October
  2018, won 83 to 96% of the time over 30 to 360 days against 55 to 70% for random dates
  ([The Block](https://www.theblock.co/post/400182/bitcoin-longest-negative-funding-streak-this-decade-k33-flags-short-squeeze-risk)).
  VanEck, April 2026, since 2020: 30-day return +11.5% against +4.5% unconditionally; funding below −5% a year was followed by
  +19.4% ([Bitcoin Magazine](https://bitcoinmagazine.com/news/vaneck-flags-dual-bullish-for-bitcoin)). **Gross, seven to nine
  episodes, method unpublished.** The one direct test of funding as a cross-sectional signal found no alpha
  ([SSRN 6701738](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=6701738)).
- **Basis.** Crypto carry averages above 10% a year and "high carry predicts future price crashes" (Schmeling, Schrimpf and
  Todorov, [BIS WP 1087](https://www.bis.org/publ/work1087.pdf)); basis is the strongest cross-sectional predictor of futures
  returns at daily horizon (Chi, Hao, Hu and Ran, *Journal of Futures Markets* 2023,
  [link](https://ideas.repec.org/a/wly/jfutmk/v43y2023i8p1161-1180.html)). Funding ran from about 10% to 30% a year in the
  week before the 10 October 2025 crash, when $19B of positions were liquidated.
- **Implied volatility** (Deribit DVOL) is a regime variable, not a directional one
  ([arXiv 2410.15195](https://arxiv.org/abs/2410.15195)).

**Used by:** the market line on Today (2.2), the leverage-heat veto (2.3) and Hated rally (2.4, Testing).

### 7.3 A research-grade trend ranking

- **CTREND.** Fieberg, Liedtke, Poddig, Walker and Zaremba, *JFQA* 60(7), November 2025: a composite of 28 trend indicators,
  weekly. The top fifth of the 100 largest coins earned 3.78% a week gross and 3.11 to 3.38% net at 30 to 50 basis points per
  trade, 2015 to 2022. **Its second half (2018 to 2022) earned 1.56% a week with t = 1.93; nothing after the ETFs.**

**Used by:** Trend leaders (2.4, Watch), to be compared with Leaders on the same coins.

### 7.4 Events: stand aside

- **New listings.** Binance's 2024 cohort from its first-day close: +2.78% next day, +0.40% at one week, −22.66% at three
  months and −37.64% at six, with 5.5% above the first close at six months
  ([Empirica](https://empirica.io/blog/the-binance-effect-a-7-year-analysis-for-token-founders/)). Of 2025's token launches,
  85% were below their launch price at year end. **Used by:** the universe's minimum age, raised from 90 to 180 days (2.2).
- **Delisting notices** come 3 to 14 days before the halt; WAVES fell about 30%, OMG 27% and XEM over 30% on Binance's
  notice ([The Block](https://www.theblock.co/post/298063/binance-to-delist-waves-omg-and-xem-on-june-17-sparking-price-plunges)),
  TORN 57% in a day. **Used by:** the notices feed (2.2) and the delisting veto (2.3).
- **Token unlocks**: about 90% of 16,000 events were followed by weakness, worst above 5% of supply (Keyrock, via
  [CryptoSlate](https://cryptoslate.com/90-of-token-unlocks-drive-prices-down-declines-begin-a-month-ahead/)). No free unlock
  calendar remains, so this veto waits.

### 7.5 Hourly trend, retired

Over its first 121 live calls (16 to 21 October 2026) Hourly trend won 10% of the time, lost 0.74% per call after fees and
sat 0.76% behind random entries, t = −2.16: past the Failing rule of §4 within five days, as §1.6 warned ("fee-fragile").
It makes no new calls from 2.2.0; its record stays in the ledger and it still counts among the strategies tried.

### 7.6 Tested and rejected

| Idea | Finding | Source |
|---|---|---|
| Buying listings or the Upbit pump | Gains come before and in the first minutes; after the first close, flat to negative | Ante 2019; [The Tie 2026](https://www.thetie.io/insights/what-does-an-exchange-listing-actually-deliver-in-2026) |
| Liquidation-flush dip buying | No forward-return evidence; capitulation cohorts underperformed | [arXiv 2607.27070](https://arxiv.org/abs/2607.27070); VanEck 2026 |
| CME weekend gaps | 80% fill against 83% for a made-up level; CME trades 24/7 since 29 May 2026 | [Tradewize](https://tradewize.io/blog/how-to-read-the-cme-gap) |
| Copy trading, whale alerts | Followers lose on average; whale alerts move volatility, not direction | Philadelphia Fed WP 26-42 |
| Exchange flows, stablecoin mints | Adjusted R² 0.001 to 0.002; issuance follows prices | Chi, Chu and Hao 2025; Kristoufek 2021 |
| Cycle-top indicators | 210 to 240 days early in 2021, silent at the October 2025 top | [arXiv 2607.26188](https://arxiv.org/abs/2607.26188) |
| Grid bots | Zero expected profit before fees | [arXiv 2506.11921](https://arxiv.org/abs/2506.11921) |
| Triangular arbitrage | 96.9% of opportunities unprofitable even at VIP 9 | Muck, Schmidl and Wolf, *FRL* 2025 |
| Machine learning on OHLCV | No robust edge over naive baselines in 23 studies | [arXiv 2606.00071](https://arxiv.org/html/2606.00071v1) |
| Fear and Greed, Google Trends, LLM headline reads | No out-of-sample return prediction | Two 2026 studies; CFRI 2025 |
| Prediction markets as a lead | No Granger-predictive content for next-hour BTC | Aalborg thesis 2026 |
| Standing funding carry | 2.0 to 3.0% a year net on BTC and ETH in 2026, below stablecoin yields | Binance funding, own pull |
| Covered calls on BTC | −0.5% net over October 2021 to April 2026 | Anchorage, 37,000 backtests |
