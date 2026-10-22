# Non-directional yield and carry strategies in crypto (2023-2026) and what a Binance-connected Android app could run

Research date: 2026-10-22. Baseline for every judgement: holding USDT in Binance Simple Earn (flexible). Where no official realised Simple Earn number exists, the DeFi stablecoin rates below (Aave v3 USDT/USDC, Sky sUSDS) are used as the nearest well-measured proxy and the gap is flagged.

**Primary data pulled in this session (not from third parties):**
- Binance USDⓈ-M funding history for BTC, ETH and 20 alts, 2021-01-01 to 2026-10-21, from the public endpoint `GET https://fapi.binance.com/fapi/v1/fundingRate` (max 1000 records per call, paged by `startTime`) — [endpoint doc](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History). Method: annualised = (sum of settlement rates in the year) × 365 / days covered; "negative days" = share of UTC days whose summed funding was < 0. Simple sum, no compounding, gross of fees and basis. Script: `C:\Users\kabas\AppData\Local\Temp\claude\C--Users-kabas-Documents-Claude-Claude-Code-signal-lab-android\7606b806-0ca2-4171-94fa-076009573e8f\scratchpad\scripts\pull_yields.py`; output `...\scratchpad\out\yields.txt`.
- Current Binance quarterly basis from `GET /fapi/v1/premiumIndex` (mark vs index for `BTCUSDT_261225`, `BTCUSDT_270326`, `ETHUSDT_261225`, `ETHUSDT_270326`), 2026-10-21 22:52 UTC.
- Daily APY history for Aave v3 USDC/USDT (Ethereum), Sky sUSDS, Morpho Steakhouse USDC, Binance WBETH, Binance BNSOL and Lido stETH from the DefiLlama yields API (`https://yields.llama.fi/pools`, `https://yields.llama.fi/chart/{pool}`), yearly mean of the daily `apy` field.

Tool note: the shared WebSearch budget ran out part-way through; a handful of planned searches (2022 GBTC/stETH carry post-mortems, Binance's own Arbitrage Bot FAQ, the Dual Investment / staking / Auto-Invest API catalogue pages) could not be run and are listed under Gaps.

---

## Key question 1 — Funding-rate carry (long spot, short perpetual) on Binance

### Takeaway
Binance BTC funding carry paid roughly 12% gross in 2024, 5% in 2025 and about 3% annualised in 2026 to date, with the share of negative-funding days rising from 7% to 24%; the top-20 alts averaged about +11% in 2024, +1.3% in 2025 and about 0% in 2026 YTD (own computation). After round-trip fees on two legs and the margin buffer needed to survive a large rally, 2025-2026 BTC/ETH carry has not clearly beaten a plain stablecoin yield, and the October 2025 crash showed the two blow-up modes (ADL of the hedge, and collateral marked on a dislocated venue).

### Cited Findings

**Binance BTC/ETH funding by year (own computation from Binance public API, 2026-10-21):**

| Symbol | Year | Annualised funding (gross) | Days with negative funding | Settlements negative |
|---|---|---|---|---|
| BTCUSDT | 2021 | 30.6% | 7.9% | 7.3% |
| BTCUSDT | 2022 | 4.2% | 19.7% | 22.1% |
| BTCUSDT | 2023 | 7.9% | 7.7% | 10.1% |
| BTCUSDT | 2024 | 11.9% | 6.6% | 8.4% |
| BTCUSDT | 2025 | 5.1% | 8.2% | 12.9% |
| BTCUSDT | 2026 YTD (to 21 Oct) | 3.0% | 23.8% | 25.7% |
| ETHUSDT | 2021 | 37.5% | 3.3% | 4.1% |
| ETHUSDT | 2022 | 0.8% | 33.4% | 34.2% |
| ETHUSDT | 2023 | 8.3% | 6.3% | 9.1% |
| ETHUSDT | 2024 | 13.0% | 3.0% | 4.2% |
| ETHUSDT | 2025 | 4.9% | 10.4% | 16.2% |
| ETHUSDT | 2026 YTD | 2.0% | 29.3% | 29.4% |

Source: computed from [GET /fapi/v1/fundingRate](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History). The median settlement in 2024 for BTC and ETH was exactly 0.01% per 8h (10.95% annualised), i.e. Binance's default interest component with zero premium.

- Cross-check: Ethena's own risk page (data to 31 Dec 2024, open-interest/volume-weighted across exchanges) gives BTC funding averaging 7.80% and ETH 9.15% annualised over three years, with 15.9% (BTC) and 17.5% (ETH) of days negative and the longest negative streak 13 days; adding ~3% staked-ETH yield cut negative-revenue days to 8.84% — [Ethena docs, funding risk](https://docs.ethena.fi/solution-overview/risks/funding-risk). My 2022-2024 BTC average (8.0%) matches.
- Cross-check: Coin Metrics (cited in a Q1 2026 Ethena report) puts BTC/ETH perp funding at about 11% annualised in 2024 and about 5% in 2025 — [Stablecoin Insider, Ethena Q1 2026 report](https://stablecoininsider.org/ethena-usde-q1-2026-report/). Matches my 11.9% / 5.1%.
- CoinGecko's 2025 perpetuals report says aggregate BTC funding was positive on all but 26 days of 2024 (search summary; the PDF could not be text-extracted in this session) — [CoinGecko, State of Crypto Perpetuals Market](https://assets.coingecko.com/reports/2025/CoinGecko-State-of-Crypto-Perpetuals-Market.pdf). My Binance-only count is 24 negative days (6.6% of 366).
- Monthly BTC funding, Binance, annualised (own computation): 2024-03 peak 37.0%; 2024-08 1.5% (42% of days negative); 2025: Jan 8.5, Feb 5.1, Mar 2.1, Apr 2.6, May 5.1, Jun 2.9, Jul 8.6, Aug 7.1, Sep 5.3, Oct 3.6 (29% of days negative), Nov 5.4, Dec 5.0; 2026: Jan 5.4, Feb −0.8, Mar −1.1, Apr −2.2, May 2.7, Jun 2.5, Jul 6.7, Aug 7.3, Sep 5.5, Oct (to 21st) 3.7. Source as above.
- K33 Research: during the May 2024 sub-neutral streak annualised funding averaged 5.1%; the 30-day average turned negative in Sept 2024 for only the seventh time since 2018 — [The Block on K33](https://www.theblock.co/post/314382/bitcoin-perpetual-futures-funding-rates-pessimism); [The Block on K33, year-end 2024](https://www.theblock.co/post/315831/perp-signal-bitcoin-bottom-bullish-year-end-k33). A vendor blog says the Binance BTC 30-day average was below zero from about 1 March 2026 (consistent with my Feb-Apr 2026 numbers) — [Ventureburn](https://ventureburn.com/bitcoin-perpetual-funding-rates-compared-across-exchanges-in-2026/) (single source, vendor).

**Top-20 alts on Binance, annualised funding and negative-day share (own computation, same method):**

| Symbol | 2024 | 2025 | 2026 YTD | Neg. days 2025 |
|---|---|---|---|---|
| BNB | −3.3% | −2.1% | +2.8% | 30% |
| SOL | 13.6% | 0.4% | −0.8% | 39% |
| XRP | 14.2% | 3.5% | −0.1% | 26% |
| DOGE | 14.0% | 4.3% | 3.1% | 19% |
| ADA | 14.0% | 4.1% | 1.5% | 23% |
| AVAX | 10.5% | 0.1% | 1.3% | 39% |
| LINK | 13.3% | 5.2% | 4.6% | 16% |
| TRX | 3.4% | −0.5% | −10.0% | 40% |
| DOT | 11.5% | 1.1% | −4.1% | 33% |
| LTC | 14.4% | 5.6% | 3.2% | 15% |
| BCH | −1.1% | −2.6% | −2.8% | 47% |
| UNI | 13.5% | 6.3% | 4.3% | 10% |
| NEAR | 14.0% | 2.1% | 4.0% | 32% |
| APT | 16.4% | −12.7% | −5.3% | 50% |
| SUI | 10.3% | 3.4% | 3.0% | 24% |
| TON | 14.2% | 4.1% | −6.0% | 22% |
| HBAR | 10.7% | 2.4% | 1.7% | 32% |
| XLM | 11.8% | −2.3% | −0.3% | 50% |
| 1000PEPE | 20.1% | 3.8% | −1.2% | 26% |
| 1000SHIB | 13.3% | 0.0% | 0.1% | 43% |
| **Simple average** | **11.4%** | **1.3%** | **−0.1%** | |

2022 for reference: SOL −38.0% (min settlement −2.0%, FTX period), BNB −12.6%, TRX −15.8%, DOT −14.3%, APT −31.9% (74 days). BNB funding on Binance was net negative every year 2022-2025. Source as above.

**Binance funding mechanics that matter for sizing:**
- Interest component 0.01% per 8h (≈10.95%/yr) is the baseline when premium is zero; caps for ~34 majors are 0.75 × maintenance margin ratio, flat ±2% for the rest; funding settles hourly once a contract hits its cap, rule live since 2 May 2025 — [Coincub](https://coincub.com/blog/perpetual-futures-funding-rates-78-of-a-quarter-at-0-01/) (secondary; cap/interval figures confirmed by the API below).
- `GET /fapi/v1/fundingInfo` (2026-10-21) returned 812 symbols: BTCUSDT and ETHUSDT cap/floor ±0.30% per 8h; SOL/XRP/BNB/ADA/LINK/LTC/BCH/AVAX ±0.375%; DOGE/DOT/TRX/1000SHIB ±0.4875%; HBAR/NEAR/APT/1000PEPE ±2%; interval distribution among listed symbols: 466 at 4h, 343 at 8h, 3 at 1h — [endpoint doc](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History) (sibling endpoint).
- Funding history endpoint: `GET /fapi/v1/fundingRate`, params symbol/startTime/endTime/limit (max 1000), ascending, shares a 500 requests / 5 min / IP budget with `fundingInfo`; `GET /fapi/v1/premiumIndex` returns `markPrice`, `indexPrice`, `lastFundingRate`, `interestRate`, `nextFundingTime` — [Binance docs](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History).

**What went wrong (2022 and 2025):**
- 2022: Binance BTC carry fell to 4.2% gross with 19.7% negative days; ETH to 0.8% with 33% negative days (ETH PoW-fork arbitrage pushed funding deeply negative in one quarter, the only negative quarter in Ethena's 3-year backtest) — own computation and [Ethena docs](https://docs.ethena.fi/solution-overview/risks/funding-risk). Alt carry was deeply negative (SOL −38%). Chainalysis put 2022 realised losses at $20.5B (Terra), $33B (Celsius + Three Arrows) and $9B (FTX) — [The Block](https://www.theblock.co/post/195244/crypto-markets-terra-celsius-3ac-ftx); these are wallet-level, not basis-trade-specific.
- Early October 2025: BTC/ETH funding climbed from ~10% to nearly 30% annualised by 6 Oct — [FTI Consulting](https://www.fticonsulting.com/insights/articles/crypto-crash-october-2025-leverage-met-liquidity). On 10 Oct more than $19B was liquidated in ~24h, ~87% longs, >1.6M accounts — [Galaxy Research](https://www.galaxy.com/insights/research/cryptos-flash-crash-liquidation-binance-adl-auto-deleveraging); [insights4vc](https://insights4vc.substack.com/p/inside-the-19b-flash-crash). Open interest fell 27.5% (~$60B to $158B) — [CoinGecko explainer](https://www.coingecko.com/learn/october-10-crypto-crash-explained).
- Hedge destruction: "some well-hedged shorts had their positions reduced or fully closed by auto-deleveraging on October 10, turning hedged books into naked exposure" — [FTI Consulting](https://www.fticonsulting.com/insights/articles/crypto-crash-october-2025-leverage-met-liquidity). ADL "kicked in on multiple venues"; "trims winners" in sparse books — [Galaxy](https://www.galaxy.com/insights/research/cryptos-flash-crash-liquidation-binance-adl-auto-deleveraging). Hyperliquid reportedly force-closed >$10B in its first ADL event in two years; "delta-neutral basis trades across venues that ADL'd on the short leg" are listed among the losers — [insights4vc](https://insights4vc.substack.com/p/inside-the-19b-flash-crash) (opinion newsletter, unnamed sources).
- Collateral marking: on Binance USDe traded to ~$0.65, WBETH to ~$430 and BNSOL to ~$34.90 (80-90% discounts) within minutes (21:42-21:51 UTC) while USDe stayed near $1 elsewhere; Binance margin engines marked collateral at the venue-local print and liquidated — [Galaxy](https://www.galaxy.com/insights/research/cryptos-flash-crash-liquidation-binance-adl-auto-deleveraging); [21Shares](https://www.21shares.com/en-us/insights/why-did-ethenas-stablecoin-remain-stable-onchain-but-depegged-on-binance). Binance paid ~$283M in compensation within 24h to users liquidated while holding those assets as collateral across Margin, Futures and Loans — [Decrypt](https://decrypt.co/344026/binance-reimburses-283m-after-market-crash-and-asset-depegging-issues); Galaxy puts the total near $300M plus a $100M low-cost loan programme for market makers, with no clawbacks — [Galaxy](https://www.galaxy.com/insights/research/cryptos-flash-crash-liquidation-binance-adl-auto-deleveraging). Binance's insurance fund reportedly paid ~$188M of bad debt — [insights4vc](https://insights4vc.substack.com/p/inside-the-19b-flash-crash) (single source).
- After the crash funding arbitrage yields fell "below 4%" — [CoinGecko explainer](https://www.coingecko.com/learn/october-10-crypto-crash-explained) (secondary); my Binance data shows BTC funding at 3.6% annualised for October 2025 with 29% of days negative.

**Binance's own product:**
- Binance launched a "Funding Rate Arbitrage Bot" on 15 May 2024: a delta-neutral spot + perpetual strategy with "Positive Carry" (buy spot, short perp) and "Reverse Carry" (sell spot, long perp) modes; both legs placed simultaneously; managed in the trading-bots wallet. The release states no fees, no performance figures, no minimums; it warns funding can reverse, spot-futures gaps can delay closing, and the margin balance may be liquidated — [press release via The Defiant](https://thedefiant.io/news/press-releases/binance-delivers-on-community-requests-for-more-choices-with-new-funding-rate-arbitrage-bot-and-full-availability-of-spot-copy-trading). No official FAQ with fee or realised-performance data was found. Third-party "4.5-10.5% monthly" claims are promotional with no method — [cryptotoday.it](https://cryptotoday.it.com/?p=19661) (disregard).
- Institutional benchmark: 1Token's funding-fee-arbitrage index (six teams, four on Binance, two on OKX, combined AUM > $1.5B, 1 Sep-1 Dec 2024) reports results only in charts; no fee modelling; concentration β = 0.033 ("highly diversified") — [1Token](https://blog.1token.tech/strategy-index-funding-fee-arbitrage-i/).

**Ethena USDe as the institutional version:**
- sUSDe average APY since inception ≈ 10.34%; 2025 range ≈ 4-15%; 30-day 4.74% and 90-day 4.53% as of 14 Oct 2026; yield sources are perp funding, staked-ETH rewards and stablecoin/T-bill income used when funding is below Treasury yields — [Stablecoin Insider](https://stablecoininsider.org/ethena-usde-q1-2026-report/). Aavescan's ERC-4626 vault data shows 2026 monthly readings between 3.49% (April) and 4.83% (1 Oct 2026) — [Aavescan](https://aavescan.com/rates/ethena-susde).
- Supply: launched Feb 2024; peaked above $14B in 2025 ($14.8B on 10 Oct 2025); $12.6B by 12 Oct; ~$5.92B on 16 Mar 2026; $4.66B on 21 Oct 2026 — [Stablecoin Insider](https://stablecoininsider.org/ethena-usde-q1-2026-report/); [21Shares](https://www.21shares.com/en-us/insights/why-did-ethenas-stablecoin-remain-stable-onchain-but-depegged-on-binance).
- Risk events: Feb 2025 Bybit hack ($1.4B) — no USDe loss because collateral sat in off-exchange custody; Oct 2025 — on-chain low ≈ $0.97, Binance low ≈ $0.65, mint/redeem stayed open, no realised loss to holders; OKX's CEO blamed a Binance USDe campaign offering up to 12% APY with USDe as collateral, which Binance and others disputed; lawsuits against Binance began Feb 2026 — [Stablecoin Insider](https://stablecoininsider.org/ethena-usde-q1-2026-report/); [21Shares](https://www.21shares.com/en-us/insights/why-did-ethenas-stablecoin-remain-stable-onchain-but-depegged-on-binance).

**Portfolio margin / small accounts:**
- On 23 Oct 2024 Binance removed the 10,000 USDT minimum (Cross Margin + Futures wallets) for Portfolio Margin; ordinary users can enable it; 350+ collateral assets — [Blockchain.News](https://blockchain.news/news/binance-eliminates-minimum-wallet-balance-portfolio-margin). "Portfolio Margin Pro" is invite-only with a different margining model and tiered collateral ratios by equity band (changes effective 31 Jul and 7 Aug 2026) — [tread.fi docs](https://docs.tread.fi/account-creation-and-api-key-connection/connecting-to-exchanges/binance/binance-portfolio-margin-pro); [Binance Square announcement](https://www.binance.com/ar/square/post/349544511029345). An older article cites a 100,000 USDT requirement for non-VIP users — [InvestingCube](https://www.investingcube.com/cryptocurrency/binance-onboards-non-vip-users-onto-its-portfolio-margin-for-cross-margin-and-futures/) (date unclear; conflicts with the Oct 2024 removal).
- The BIS "Crypto carry" paper (Schmeling, Schrimpf, Todorov; Apr 2023, rev. Oct 2025) finds BTC/ETH carry averages above 10% a year and reaches 60%; it is driven by convenience yield (retail leveraged demand and scarce arbitrage capital), high carry predicts crashes, and margin spikes during drawdowns force carry positions closed — [BIS WP 1087](https://bis.org/publ/work1087.htm).

### Inferences
- Net APR arithmetic (estimate; Binance VIP-0 fees could not be read from the live fee page this session — it rendered placeholders; the standard published schedule is 0.10% spot and 0.02%/0.05% maker/taker on USDⓈ-M futures, so a taker round trip on both legs is about 0.30% of notional, plus the entry/exit spot-perp spread, which the app can read as `markPrice − indexPrice` from `premiumIndex`): 2024 BTC ≈ 11.9% − 0.3% ≈ 11.6% on notional; 2025 ≈ 4.8%; 2026 YTD ≈ 2.7%. Against Aave v3 USDT (6.3% in 2024, 4.0% in 2025, 3.0% 2026 YTD, see Q4) carry beat stablecoin yield clearly only in 2024.
- Margin to survive a 50% rally without liquidation on the short leg if margin is USDT only: initial margin ≥ ~51% of notional (50% loss + ~0.4-0.5% maintenance at low tiers), so capital ≈ 1.5× notional and yield on capital ≈ two-thirds of the notional figure (2025: ≈ 3.2%). Using the spot leg itself as collateral (Multi-Assets Mode or Portfolio Margin, with the spot asset's haircut) makes the position largely rally-proof because the spot gain offsets the perp loss; the residual risk is then the haircut, funding drain and venue-specific marking (the Oct 2025 WBETH/BNSOL case). Both are inferences from Binance's margin rules, not measured.
- Alt carry is a 2024 phenomenon: the 20-alt average of +1.3% gross in 2025 and ~0% in 2026 is below fees. BNB carry on Binance is structurally negative, so a hedged Launchpool farmer (long BNB spot, short BNB perp) pays about 2-3% a year in funding rather than earning it.
- The two blow-up modes are not in the funding series: (1) ADL closing the short while spot stays, and (2) collateral marked at a dislocated venue print. A ledger that only records funding will overstate realised carry.

### Gaps
- No official Binance fee page could be read this session (placeholders); fee figures above are the standard published schedule and should be verified from the account's `commissionRate` endpoints.
- No independent realised-performance data for Binance's Arbitrage Bot; its fee treatment (whether it uses standard trading fees only) is unconfirmed.
- 2022 post-mortems specific to carry (GBTC premium arbitrage, stETH discount, FTX collateral) were not fetched because the search budget ran out; the 2022 numbers above are funding data only.
- CoinGecko's 2025 perps report could not be text-extracted; its 2024 "26 negative days" figure comes from the search summary.
- My funding statistics are Binance USDⓈ-M only, simple sums, gross of fees and basis; longest negative streaks were not computed.

---

## Key question 2 — Quarterly futures cash-and-carry (Binance and CME), 2024-2026

### Takeaway
The quarterly basis compressed from double digits in 2024/early 2025 to roughly 3% on CME (below two-year Treasuries) by mid-2026, and ~5% annualised on Binance's Dec-2026 and Mar-2027 BTC contracts on 2026-10-21; for a retail account the fee drag on a two-month hold eats a third of that, leaving a net return at or below DeFi stablecoin yields.

### Cited Findings
- CME three-month annualised basis ≈ 3% vs 3.8% on two-year Treasuries (Aug 2026); "regularly exceeded 15-20% during 2024 and the first half of 2025" (article claim); CME BTC open interest fell from ~175,000 BTC at the start of 2026 to ~103,000 BTC by August, below Binance's ~148,500 BTC, the first time since late 2023; leveraged funds flipped from persistent net short (2024-2025) to net long — [crypto.news, 17 Aug 2026](https://crypto.news/binance-reclaimed-futures-dominance-cme-institutional-pullback/) (secondary; the 15-20% claim is single-source). Same flip reported by [CoinDesk, 10 Aug 2026](https://www.coindesk.com/markets/2026/08/10/a-rare-cme-shift-hedge-funds-abandon-structural-shorts-to-bet-on-a-bitcoin-rally).
- CME activity hit a 14-month low in April 2026 (average daily OI under $8B, early April $7.2B) — [LBank news aggregator citing The Block](https://www.lbank.info/news/cme-bitcoin-futures-activity-slumps-to-14-month-low-as-basis-trade-unwind-drains-institutional-demand).
- 2025: CME OI rose from ~30,000 contracts (early 2024) to 45,000 (Nov 2024) and eased to the low-30,000s by May 2025; the basis "briefly dipped below zero in February 2025"; a 10x Research analyst called a ~4.3% spread a significant decline in arbitrage activity — [Seeking Alpha](https://seekingalpha.com/article/4839008-spot-etfs-give-rise-to-crypto-basis-trading) (paywalled; from search summary; single source).
- U.S. spot BTC ETFs saw $5.4B net outflows in H1 2026, the first negative half-year since launch — [crypto.news](https://crypto.news/binance-reclaimed-futures-dominance-cme-institutional-pullback/).
- Binance quarterly annualised basis was 7.6% at end-Q3 2025 with perp funding at 2.1% — [ARK Invest, The Bitcoin Quarterly Q3 2025](https://assets.arkinvest.com/media-8e522a83-1b23-4d58-a202-792712f8d2d3/df54ea1a-91b6-4cdd-8998-9b41bccd35f2/The%20Bitcoin%20Quarterly%20(Q3%202025).pdf).
- Binance snapshot 2026-10-21 22:52 UTC (own pull from `/fapi/v1/premiumIndex`): BTCUSDT_261225 mark 85,270 vs index 84,507, 65 days to expiry, basis 0.90%, 5.07% annualised; BTCUSDT_270326 basis 2.21%, 156 days, 5.18%; ETHUSDT_261225 0.74%, 4.16%; ETHUSDT_270326 1.94%, 4.55% — [endpoint doc](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History).
- Binance quarterly (delivery) contracts carry no funding fee — [Binance FAQ](https://www.binance.com/en/support/faq/what-are-perpetual-futures-and-quarterly-futures-d2a1afd5f829455c9ded23f0ca561a40).
- Basis annualisation convention: (futures/spot − 1) × 365 / days to expiry — [Sharpe.ai guide](https://www.sharpe.ai/learn/futures-basis-explained) (educational).

### Inferences
- Retail net APR on Binance today (estimate): 5.1% basis on the 65-day contract minus ~0.30% round-trip taker fees spread over 65 days (≈ 1.7% annualised drag) ≈ 3.4%; on the 156-day contract ≈ 4.5%. Both are at or below Aave USDT (3.0% 2026 YTD) and Sky sUSDS (3.7%), and only beat Binance Simple Earn if its base rate is in the ~1-3% range (see Q3).
- Roll mechanics the app would need: hold spot, short the current-quarter USDⓈ-M delivery contract, let it settle at expiry (no funding, cash-settled to index) or close and re-open the next quarter a week or two before expiry; the roll costs another round trip and exposes the trade to the calendar spread. The ETF-driven basis trade (long ETF, short CME) is not reproducible from Binance, but its compression is why the Binance basis is now ~5%.

### Gaps
- No primary CME/CFTC quarterly series of the annualised basis by quarter 2024-2026 was obtained; the Seeking Alpha and crypto.news figures are secondary and partly conflicting (one April 2026 piece said ~5% vs ~4.5% risk-free).
- No K33/Kaiko Binance quarterly basis history was found.
- Binance delivery-settlement rule (index averaging window) not verified this session.

---

## Key question 3 — Binance Earn for a retail account via API

### Takeaway
Simple Earn is fully API-addressable (22 endpoints under `/sapi/v1/simple-earn/`, including personal rate history, so the app can measure its own realised baseline), but no official realised 2024-2026 USDT rate exists publicly; Launchpool/HODLer/Megadrop have no API and their headline APYs are Binance-reported, short-window, and inflated by BNB's 2024 price rise; Dual Investment is a sold option whose pricing against the option market is undisclosed.

### Cited Findings
**Simple Earn API (confirmed via a wrapper that mirrors the official paths):**
- GET `/sapi/v1/simple-earn/account`, `/flexible/list`, `/flexible/position`, `/flexible/subscriptionPreview`, `/flexible/personalLeftQuota`, `/flexible/history/rateHistory`, `/flexible/history/rewardsRecord`, `/flexible/history/subscriptionRecord`, `/flexible/history/redemptionRecord`, `/flexible/history/collateralRecord`, `/locked/list`, `/locked/position`, `/locked/subscriptionPreview`, `/locked/personalLeftQuota`, `/locked/history/subscriptionRecord`, `/locked/history/redemptionRecord` (all USER_DATA); POST `/flexible/subscribe`, `/flexible/redeem`, `/locked/subscribe`, `/locked/redeem` (TRADE); POST `/flexible/setAutoSubscribe`, `/locked/setAutoSubscribe` (USER_DATA) — [binance Elixir wrapper docs](https://binance.hexdocs.pm/Binance.SimpleEarn.html); official change log (e.g. `sourceAccount` parameter added to flexible subscribe 2023-08-26) — [Binance developers](https://developers.binance.com/docs/simple_earn/change-log).
- Binance's own terms: flexible rewards = a Real-Time APR that "is subject to change every minute" plus an occasional Bonus Tiered APR on selected products — [Binance Square post quoting the terms](https://www.binance.com/de/square/post/349868913586274).
- Rates: a Binance Square LATAM promotion post describes USDT at "around a 1% Real-Time APR" plus up to 30% Bonus Tiered APR on the first 2,000 USDT for up to 7-14 days — [Binance Square](https://www.binance.com/de/square/post/349868913586274) (promotional, single post, undated). Third-party 2026 guides put stablecoin flexible yields at roughly 2-5% — [EarnPark](https://earnpark.com/en/posts/binance-earn-review-2026/) (vendor). Promotions: USD1 flexible up to 8.5% APR incl. bonus tier (Jul-Aug 2026), USD1 20% APR (24 Dec 2025-23 Jan 2026), "U Simple Earn" campaign up to 8% — [Traders Union](https://tradersunion.com/news/brokers-news/show/2293414-binance-unveils-new-earn-promotion/); [Traders Union](https://tradersunion.com/news/brokers-news/show/2216298-binance-launches-united-stables-promotion/). The public USDT Earn page renders only placeholders — [Binance Earn USDT](https://www.binance.com/en-AE/earn/USDT).
- DeFi comparators for the same years (own computation from DefiLlama daily APY): Aave v3 USDT (Ethereum) mean 4.14% (2023), 6.31% (2024), 4.00% (2025), 2.99% (2026 YTD); Aave v3 USDC 3.82%, 7.17%, 4.12%, 3.37%; Sky sUSDS 4.75% (2025, from Feb), 3.72% (2026 YTD); Morpho Steakhouse USDC 3.96% (Dec 2025 only), 3.91% (2026 YTD) — [DefiLlama yields API](https://yields.llama.fi/pools).

**Launchpool / HODLer Airdrops / Megadrop:**
- Binance-sourced analysis: Jan 2024-Mar 2025 "combined returns of 177%" for BNB kept on Binance, of which BNB price +104% ($313 to $640) and "$226 in token rewards" per BNB from Launchpool, Megadrop and HODLer Airdrops; Launchpool "average APYs hit 84%" using first-day closing prices; 21 Launchpools in 2024 distributed over $1.75B; SAGA paid ~$13.07 and ENA ~$10 per staked BNB; Megadrop + HODLer "19.7% additional yield" — [Blockchain.News](https://blockchain.news/news/bnb-177-percent-returns-launchpool-airdrops-2025); [MEXC news mirror](https://www.mexc.com/news/910567). A CoinMarketCap-based study cited in the same coverage put combined 2024 yields at 53-78%.
- A 2026 guide (promotional, referral links) gives $226/BNB for Launchpool and ~$120/BNB for Megadrop + HODLer, estimates 2025 Launchpool APY at 60-100% across ~20 projects and H1 2026 at 70-110% across ~10 projects (estimates, unsourced), lists HODLer examples BERA 328.5%, KAITO 107.0%, LAYER 106.1%, SIGN 55.9% APY, minimum 0.01 BNB, Megadrop lock options 30/60/90/120 days with 1x-3x multipliers — [Coinvado](https://coinvado.com/en/posts/binance-launchpad-megadrop-airdrop-guide-2026/). Numbers conflict with the Blockchain.News total.
- Programme cadence: Fabric Protocol (ROBO) was the 62nd HODLer Airdrop (18 Mar 2026) and Genius Terminal the 65th (snapshot 11-13 May 2026, 10M GENIUS) — [MEXC news](https://www.mexc.com/news/952763); [Whale Alert](https://whale-alert.io/stories/babb017131c665/BNB-price-reclaims-670-after-VanEck-launch-as-Binances-Genius-Terminal-HODLer-airdrop-remains-in-focus). HODLer eligibility: BNB in Simple Earn (flexible or locked) during random hourly snapshots; KYC; eligible jurisdiction — [Cointelegraph](https://cointelegraph.com/news/binance-revamps-launchpool-streamlines-the-bnb-experience).
- API: no Launchpool, HODLer or Megadrop endpoints appear in Binance's developer docs or in the wrapper's module list (which does include SimpleEarn, Staking, AutoInvest, PortfolioMargin) — [wrapper module index](https://binance.hexdocs.pm/api-reference.html); [Binance developers quick start](https://developers.binance.com/docs/simple_earn). Because HODLer Airdrops key off BNB Simple Earn balances, subscribing BNB to flexible Simple Earn via API is the programmatic way to qualify.

**Dual Investment and Auto-Invest:**
- Dual Investment "works by selling options contracts to options buyers. The premium that is generated by the option seller is what powers the yield"; yield = principal × APR × days / 365; above-strike at expiry the deposit converts at the strike plus premium, below it you keep the asset plus premium; non-principal-protected; tenors from 1 day to 180+ days — [RugDoc](https://wiki.rugdoc.io/es/docs/introduction-to-binance-dual-investment/); [Mobee help centre](https://mobee.com/en/app/help-center/dual-investment). "50% APR over 3 days is ~0.41% on your deposit"; higher APR always comes with a strike closer to spot; settlement uses a time-weighted average across major exchanges — [KuCoin guide](https://www.kucoin.com/blog/dual-investment-explained-how-to-turn-your-target-price-into-field).
- Auto-Invest has REST endpoints (module `Binance.AutoInvest`, "Account/Trade") — [wrapper module index](https://binance.hexdocs.pm/api-reference.html).

### Inferences
- The app can measure the honest baseline directly: subscribe USDT to flexible Simple Earn via `POST /flexible/subscribe` and read the realised rate from `/flexible/history/rateHistory` and `/flexible/history/rewardsRecord`; that avoids relying on any advertised APR.
- Launchpool "84% APY" is an annualisation of a few-day farming window, not an annual return; the reward-only return on BNB in 2024 was roughly $226 / $313 ≈ 72% (or ~$346/$313 if the $120 Megadrop+HODLer figure is additive), but this is Binance's own data in a year when BNB doubled and new tokens listed at high first-day prices. No 2025-2026 reward-per-BNB series exists publicly, so a farming-vs-holding comparison for 2025-2026 cannot be made.
- Dual Investment APRs are option premia quoted gross of an undisclosed exchange margin; without a Deribit comparison (not obtained) fairness cannot be judged. See Q6.

### Gaps
- No official or independently measured realised Binance Simple Earn rate for USDT, USDC, FDUSD or BNB for 2024-2026; the "~1% Real-Time APR" anchor is a single promotional post. The app should record its own.
- Dual Investment and ETH/SOL staking endpoints exist in Binance's catalogue by my recollection (`/sapi/v1/dci/...`, `/sapi/v2/eth-staking/...`, `/sapi/v1/sol-staking/...`) but were not verified this session (the catalogue pages redirected to a landing page; search budget exhausted). Treat as unverified.
- No per-event HODLer/Megadrop reward-value tracker for 2025-2026 was found.

---

## Key question 4 — Stablecoin yields: on-exchange vs DeFi, 2024-2026, and what broke

### Takeaway
Measured DeFi stablecoin lending yields fell from 6-7% (2024) to ~4% (2025) and ~3-3.7% (2026 YTD), with sUSDe at ~4.5-4.9% after averaging ~10% since inception; the realised losses of 2025-2026 came from curated/levered "yield" tokens (Stream xUSD, Elixir deUSD, USDX) and exploits (Balancer, Kelp DAO → Aave bad debt), not from the blue-chip lending rate itself.

### Cited Findings
- Aave v3 (Ethereum) daily APY yearly means (own computation, DefiLlama): USDC 3.82% (2023), 7.17% (2024, max 41.4%), 4.12% (2025, max 10.0%), 3.37% (2026 YTD, 294 days); USDT 4.14%, 6.31%, 4.00%, 2.99% — [DefiLlama yields API](https://yields.llama.fi/pools). A vendor guide's "2025 historical average 5.9%" for Aave USDC — [RebelFi](https://rebelfi.io/blog/usdc-yield-aave-v3-institutional-guide) — is higher than the measured 4.12% and should be disregarded.
- Sky sUSDS (governance-set Sky Savings Rate): mean 4.75% in 2025 (range 4.00-8.75%), 3.72% 2026 YTD (3.52-4.00%), 3.80% on 21 Oct 2026, TVL $4.8B — [DefiLlama](https://yields.llama.fi/pools). Morpho Steakhouse USDC: 3.91% 2026 YTD (range 2.13-11.32%) — same source.
- Ethena sUSDe: ~10.34% average since Feb 2024; 2025 ~4-15%; 4.74% 30-day / 4.53% 90-day on 14 Oct 2026 — [Stablecoin Insider](https://stablecoininsider.org/ethena-usde-q1-2026-report/); 2026 monthly 3.49-4.83% — [Aavescan](https://aavescan.com/rates/ethena-susde). A Pendle newsletter recorded sUSDe at 37.2% APY at one point (undated snippet) — [Pendle Print #17](https://pendlefi.substack.com/p/the-pendle-print-17).
- Pendle fixed yields: a June 2026 Aave governance post shows PT-sUSDe fixed 4.50% against an underlying 3.84% at deployment — [Aave governance](https://governance.aave.com/t/direct-to-aip-onboard-pt-susde-22oct2026-to-aave-v3-plasma/25129/2). Mechanics (PT locks the discount as a fixed rate; YT pays realised yield) — [eco.com explainer](https://eco.com/support/en/articles/15253995-what-is-pendle-pt-and-yt-tokens-explained-2026).
- Failures: Stream Finance disclosed a ~$93M loss on 4 Nov 2025 after an external manager's leveraged positions were liquidated in the 10 Oct crash; ~$285M owed to lenders incl. ~$68M to Elixir; xUSD fell to ~$0.26 (some sources $0.10); Elixir's deUSD, ~65% lent to Stream, fell ~98% to ~$0.015 and was wound down with ~80% of holders redeemed in USDC; Stables Labs USDX broke below $0.60; lending vaults had priced xUSD at a hard-coded $1 so liquidations never fired — [Cointelegraph](https://cointelegraph.com/news/elixir-sunsets-stablecoin-amid-stream-finance-saga); [Pharos case study](https://pharos.watch/learn/case-studies/stream-elixir-contagion-2025/); [BlockEden](https://blockeden.xyz/blog/2025/11/08/m-defi-contagion/).
- Balancer hack "lost over $98 million with partial recovery" (Nov 2025) — [Zircuit blog](https://www.zircuit.com/blog/behind-cryptos-biggest-collapses) (single mention, amount unverified). April 2026 Kelp DAO bridge exploit caused ~$196M bad debt on Aave — [AlphaGrowth](https://alphagrowth.substack.com/p/best-stablecoin-yields-on-base-in) (single source).
- Bybit hack Feb 2025 $1.4B; Ethena unaffected due to off-exchange custody — [Stablecoin Insider](https://stablecoininsider.org/ethena-usde-q1-2026-report/).

### Inferences
- Advertised-vs-realised gap: curated vault and promotional APRs (Binance USD1 20%, Morpho vault spikes to 11%) are short-lived; the measured yearly means cluster at 3-4% for 2026, which is roughly the T-bill rate minus protocol take. For the app's baseline, Aave USDT or sUSDS yearly means are a defensible public proxy if the account's own Simple Earn history is too short.
- Every 2025-2026 stablecoin loss was a wrapper-level failure (rehypothecated collateral, hard-coded oracle, exchange-local marking), so a "cash sleeve" should hold the plain stablecoin in Simple Earn, not a yield-bearing synthetic.

### Gaps
- No Binance Simple Earn realised stablecoin history (see Q3).
- Balancer and Kelp DAO amounts are single-source; Pendle realised-vs-fixed comparison over 2025-2026 not found.

---

## Key question 5 — Staking (ETH, SOL, Binance liquid staking) and whether it belongs in a cash sleeve

### Takeaway
Binance WBETH has yielded 3.0% (2024), 2.7% (2025) and 2.45% (2026 YTD) and BNSOL 5.9% (2025) and 5.1% (2026 YTD) in token terms; net of issuance ETH's real yield is ~2-3% and SOL's ~1.3-3%, but both are paid in a volatile asset and the Oct 2025 crash showed WBETH/BNSOL can print 80-90% below their underlying on Binance — they do not belong in a cash sleeve.

### Cited Findings
- Binance WBETH APY yearly means (own computation, DefiLlama): 3.81% (2023, from June), 3.03% (2024), 2.68% (2025), 2.45% (2026 YTD), 2.24% on 21 Oct 2026; Lido stETH 4.53% (2022), 4.32% (2023), 3.17% (2024), 2.82% (2025), 2.37% (2026 YTD); Binance BNSOL 5.88% (2025), 5.08% (2026 YTD), 4.55% now — [DefiLlama yields API](https://yields.llama.fi/pools).
- Binance advertised "up to 5.6% APR on BNSOL and up to 2.6% APR on WBETH" (28 Nov 2025) — [Blockchain.News flash](https://blockchain.news/flashnews/BNSOL). Binance's ETH staking page says APR is dynamic, follows on-chain rewards, and a commission is charged on rewards (page shows placeholders) — [Binance ETH Staking](https://www.binance.com/en-AE/earn/ethereum-staking).
- ETH issuance ~0.35% annualised, often net deflationary after EIP-1559 burns — [ChainUp](https://chainup.com/blog/is-staking-still-profitable-2025/) (vendor); another vendor puts real yield at 3.5% − 0.8% ≈ 2.7% — [BlockEden](https://blockeden.xyz/blog/2026/02/08/solana-etf-staking-yield-revolution/) (vendor).
- SOL: inflation ~4.7% falling toward 1.5% — Coin Metrics (page now redirects; figure from search summary) [coinmetrics.io](https://coinmetrics.io/?p=20167); Aug 2026 yield ~5.25% of which ~3.78% is protocol inflation — [JPool research](https://jpool.one/research/solana-staking-report-epoch-1000); SIMD-0550 would cut modelled nominal yield from 5.84% to 4.34% (year 1), 3.00% (year 2), 2.25% (year 3) — [21Shares](https://21shares.com/en-us/insights/solana-simd-550-simd-553-staking-yield).
- Oct 10 2025: WBETH ~$430 and BNSOL ~$34.90 at trough on Binance (80-90% below underlying) while Binance was mid-way through changing their margin pricing "from price-based to redemption/fundamental references" — [Galaxy](https://www.galaxy.com/insights/research/cryptos-flash-crash-liquidation-binance-adl-auto-deleveraging).

### Inferences
- In a carry book, staked wrappers raise the gross by ~2.5% (ETH) or ~5% (SOL) but add exactly the collateral-marking risk that blew up Binance accounts in Oct 2025; only sensible if the wrapper is not used as margin collateral.
- Yields are falling year on year on both chains (ETH 3.2% → 2.4%, SOL 5.9% → 5.1%), so historical "staking yield" tables overstate forward returns.

### Gaps
- No slashing statistics and no Binance WBETH/BNSOL redemption (unbonding) time were obtained; Coin Metrics' staking report redirected.
- Vendor sources disagree on ETH inflation (0.35% vs 0.8%).

---

## Key question 6 — Options overwriting (covered calls / put writing on BTC and ETH) and Dual Investment

### Takeaway
The best available study (Anchorage, Deribit IV surface, Oct 2021-Apr 2026, 37,000+ backtests) finds a 20-delta 30-day BTC covered call earned −0.5% net over the whole period despite an 81% win rate, +5.5% in the bear 12 months to Apr 2026, and +5.2% a year only with a regime filter; an academic 2021-2025 study finds negative average expiration profits for both covered calls and protective puts — overwriting is a low single-digit, bull-market-capped yield, and Dual Investment is the same sold option with an undisclosed spread.

### Cited Findings
- Anchorage Digital: period 1 Oct 2021-30 Apr 2026; Deribit IV surfaces hourly, 9 deltas (5-45) × 6 tenors (7-90d), 108 parameter combinations, >37,000 rolling backtests; one vol point of slippage each leg, Black-76, zero rate, no exchange fees. 20-delta/30-day unfiltered: −0.5% net over the full period (−0.1% a year), 57 wins / 13 losses, profit factor 1.00; 12 months to Apr 2026: +5.5% overlay yield with BTC −19.4%, Sharpe 0.53; with regime + IV filters full period: +23.7% (+5.2% a year), BTC +74.4% vs +98.1% blended, time in market 43.8%; 5-10 delta "barely surpassing 5%"; above 25 delta "directional exposure overwhelms the strategy" even with the filter; 7- and 14-day tenors underperform; 3-year rolling medians ~4-6% a year in the 10-25 delta / 30-90 day corridor; unfiltered calls were overrun in late 2021, the 2023-24 rally and the 2025 rally above $100k — [Anchorage](https://www.anchorage.com/insights/synthetic-yield-on-bitcoin-implementation-discipline-and-performance-boundaries-of-systematic-covered-call-writing). Same paper: BTC's upside volatility risk premium (25-delta call IV minus subsequent realised upside vol) has averaged 2-3× that of SPY/QQQ since 2024.
- MDPI (Risks, 2026) on BTC protective puts and covered calls, 2021-2025: "both ... yield negative average expiration profits over the 2021-2025 sample period" — [MDPI](https://www.mdpi.com/2227-9091/14/6/126) (abstract via search summary; page returned 403).
- Academic: BTC put options "generally do not yield significant positive returns" (limited upside, positive price drift) — [Atanasova et al., Illiquidity Premium and Crypto Option Returns](https://acfr.aut.ac.nz/__data/assets/pdf_file/0006/969378/950002_Atanasova_Illiquidity-Premium-and-Crypto-Option-Returns.pdf).
- Market size: Deribit BTC options OI briefly topped $100B in late 2025, settling ~$60B — [Deribit Insights week 52 2025](https://insights.deribit.com/industry/crypto-derivatives-analytics-report-week-52-2025/). BlackRock's covered-call BTC ETF targets 15-25% annual yield by writing calls on 25-35% of NAV (unverified product claim) — [Investing.com](https://www.investing.com/analysis/coveredcall-bitcoin-etfs-test-demand-beyond-pure-spot-exposure-200682250).
- Dual Investment = short option; APR quoted gross; short tenors inflate the APR optics (50% APR for 3 days ≈ 0.41%) — [KuCoin](https://www.kucoin.com/blog/dual-investment-explained-how-to-turn-your-target-price-into-field); [RugDoc](https://wiki.rugdoc.io/es/docs/introduction-to-binance-dual-investment/).

### Inferences
- Judged against a stablecoin at 3-4%, BTC covered calls at 10-20 delta delivered a comparable 4-6% a year in token terms only over multi-year horizons and only with a filter; they are a volatility-regime bet, not a cash substitute, and their yield is paid in BTC-denominated gains forgone in rallies.
- Dual Investment "Sell High" is a covered call and "Buy Low" a cash-secured put; fairness can only be judged by comparing its implied premium with Deribit's quote for the same strike/expiry at the same minute. No published comparison was found; absent that, assume the exchange keeps a spread.
- From a phone, the app can at best compute an implied-vol-equivalent of a Dual Investment quote (Black-76 from strike, tenor, APR) and compare it with Binance Options (`eapi`) or Deribit public mark IV; it cannot write Deribit options through Binance.

### Gaps
- Deribit fee schedule page could not be read (returned only a title); the Anchorage study excludes exchange fees.
- No calendar-year breakdown of covered-call yields exists in the Anchorage paper; no Deribit/Amberdata 2023-2026 covered-call yield study was found; no Binance Options API research was done.
- No source compares Dual Investment APR with option fair value.

---

## Key question 7 — DEX liquidity provision (Uniswap v3, Meteora, Raydium)

### Takeaway
The measured evidence says passive and retail concentrated-liquidity LPs lose versus holding on average (Heimbach et al.: LPs "on average lose money in comparison to holding", "retail traders do not stand a chance"; Loesch et al.: ~50% of positions losing; a 2026 preprint finds fee APR ranks pools roughly backwards against realised net return), and it is not reachable from a Binance API account anyway — not worth a sleeve in this app.

### Cited Findings
- Heimbach, Schertenleib, Wattenhofer (Uniswap v3, 4 May 2021-31 Mar 2022; USDC-WETH, WBTC-WETH, DAI-USDC at 0.01/0.05/0.3%): in volatile pools "on average, liquidity providers lose money in comparison to holding the assets"; "less than 30% of the liquidity positions ... are rewarded" for the added risk vs stable pools; narrow ranges have the largest-magnitude (mostly negative) daily returns; stable-pair returns are small (0-0.04%/day for positions >30 days) but "never significantly negative"; conclusion: "retail traders do not stand a chance" and should "restrict themselves to simple strategies that yield only small returns" — [arXiv 2205.08904](https://arxiv.org/html/2205.08904v2).
- Loesch et al. (cited there): ~50% of Uniswap v3 positions lost money vs holding; in their 43%-of-TVL sample LPs earned $199.3M in fees against $260.1M of impermanent loss — [arXiv 2205.08904 related work](https://arxiv.org/html/2205.08904v2); [summary via Claude skills page](https://claudeskills.info/skills/agiprolabs/claude-trading-skills/dex-pool-analysis/) (secondary).
- Loss-versus-rebalancing (Milionis, Moallemi, Roughgarden, Zhang): an ETH-USDC constant-product pool loses σ²/8 of pool value per day to arbitrageurs — 3.125 bp/day at the example volatility (≈ 11% a year) — [LVR paper](https://anthonyleezhang.github.io/pdfs/lvr.pdf).
- 2026 preprint (62 Uniswap v3 pools on Ethereum, Arbitrum, Base; 37 weeks of 2026): fee APR "orders pools close to the reverse of their realized net returns" after LVR and gas — [Research Square rs-11118453](https://www.researchsquare.com/article/rs-11118453/v1) (preprint; page returned only a title; claim from search summary).
- Uniswap Labs' own fee study (Sep 2021-Mar 2022) shows v3 fee returns exceed v2 for most pairs but measures fees only, not LVR — [Uniswap blog](https://blog.uniswap.org/fee-returns).
- Solana: Meteora's own docs say its IL examples assume 69 bins with equal liquidity and exclude fees — [Meteora docs](https://docs.meteora.ag/security-and-risks/risk-of-impermanent-loss-il); vendor claims of "5-10x Raydium fees" are unsourced — [MadeOnSol](https://madeonsol.com/blog/meteora-dlmm-pools-explained). No rigorous Meteora/Raydium return study was found.

### Inferences
- LP return = fees − LVR − gas; the only persistent positive case is stable-stable pairs at near-zero return, i.e. below a stablecoin yield once gas is counted. For the app's purposes LP is "not sensibly done from a phone" and, more importantly, not reachable through Binance's API at all.

### Gaps
- No retail-specific, post-2023 measured LP return study for Solana DEXs; the Research Square preprint's numbers were not readable.

---

## Key question 8 — Fitting carry/yield into the app's honest ledger

### Takeaway
A carry strategy's fair baseline is the account's own realised Simple Earn USDT rate (readable via `/sapi/v1/simple-earn/flexible/history/rateHistory`), its "excess" is funding received minus funding paid, fees, entry/exit basis and collateral haircut, all over the same capital and time, and the ledger must record both legs' fills, every funding settlement, every fee, the margin ratio path, and any ADL/liquidation — otherwise it will report a 5% carry that in Oct 2025 was a naked long.

### Cited Findings
- Public data the app can use without keys: `/fapi/v1/fundingRate` (history, 1000 per call), `/fapi/v1/fundingInfo` (caps, intervals), `/fapi/v1/premiumIndex` (mark, index, last funding, next funding time) — [Binance docs](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Get-Funding-Rate-History).
- Keyed Simple Earn endpoints for the baseline and for cash management: `/flexible/history/rateHistory`, `/flexible/history/rewardsRecord`, `POST /flexible/subscribe`, `POST /flexible/redeem` — [wrapper mirroring official paths](https://binance.hexdocs.pm/Binance.SimpleEarn.html).
- The "no funding on quarterlies" rule means a quarterly cash-and-carry ledger records basis convergence instead of funding cash flows — [Binance FAQ](https://www.binance.com/en/support/faq/what-are-perpetual-futures-and-quarterly-futures-d2a1afd5f829455c9ded23f0ca561a40).
- Failure modes that must be ledger events, from Oct 2025: ADL closing the short leg (FTI, Galaxy); collateral marked at a venue-local print (Galaxy, 21Shares); compensation paid outside the trade (Decrypt) — sources as in Q1.
- BIS: high carry predicts crashes and margin spikes force closures — [BIS WP 1087](https://bis.org/publ/work1087.htm).

### Inferences (design proposal, derived from the above)
- **Baseline:** stablecoin yield over the identical window: realised Simple Earn USDT rate from `rateHistory` (fallback: Aave v3 USDT daily APY from DefiLlama, yearly means 6.3% / 4.0% / 3.0% for 2024 / 2025 / 2026). Compare on *capital employed*, not notional: capital = spot notional + futures margin actually posted.
- **Excess** = Σ funding received − Σ funding paid − spot fees − perp fees − (basis at entry − basis at exit) × notional − any collateral haircut/liquidation loss − baseline × capital × days/365. A positive-only funding filter ("enter when 7-day trailing annualised funding > baseline + fee hurdle") is the strategy; "always-on carry" is a second, tougher baseline the ledger can also run, since timing is the only edge the app adds.
- **Ledger records per carry position:** spot fill (price, qty, fee), perp fill (price, qty, fee, leverage, margin mode), mark and index at entry/exit (basis), each funding settlement (time, rate, notional, amount; paper mode: rate × notional from `/fapi/v1/fundingRate`; live: `FUNDING_FEE` income records — endpoint not verified this session), margin ratio snapshots (to show how close the short came to liquidation in rallies), ADL/liquidation flags, exit reason (funding below hurdle, time limit, margin stop), and the baseline accrual over the same days.
- **Random baseline analogue:** random entry dates for the same coin with the same holding period and fee model still make sense for the *timing* claim (does entering on high trailing funding beat entering at random?), while the stablecoin yield is the economic hurdle.
- **What a Kotlin Android app with Binance keys can do:**
  - *Automate fully:* Simple Earn subscribe/redeem and realised-rate logging; Auto-Invest (API exists); funding-rate monitoring and paper carry (public endpoints only); live carry legs (spot order + USDⓈ-M short) are technically possible but a phone is a poor host for a position that needs sub-minute margin management in a crash — size to survive ≥50% rallies (USDT margin ≥ ~51% of notional, or spot-as-collateral mode) and accept that ADL cannot be prevented.
  - *Size and signal only:* Launchpool/HODLer/Megadrop (no API; the signal is "BNB in flexible Simple Earn qualifies"), Dual Investment (API unverified; compute implied vol and compare), quarterly basis roll timing (basis from `premiumIndex`), ETH/SOL staking via Binance (API unverified; yields 2.4% / 5.1% in token terms).
  - *Not sensibly from a phone / not via Binance:* Deribit options overwriting, DEX liquidity provision, Ethena minting (DeFi), Pendle.
- **Honest summary numbers for the plan (gross Binance funding, own computation):** BTC 11.9% (2024), 5.1% (2025), 3.0% (2026 YTD); ETH 13.0 / 4.9 / 2.0; 20-alt average 11.4 / 1.3 / −0.1. After ~0.3% round-trip fees and the 1.5× capital buffer, 2025-2026 BTC carry on capital is ~2-3%, below Aave USDT (4.0% / 3.0%) and sUSDS (4.75% / 3.7%); the strategy only paid in 2024 and in short bursts (Mar 2024 37%, Nov 2024 16%, Jul 2025 8.6%, Aug 2026 7.3%), which is the case for a *timed* carry signal rather than a standing allocation.

### Gaps
- Binance futures income endpoint (`/fapi/v1/income`, `incomeType=FUNDING_FEE`) and the account commission-rate endpoints were not verified this session.
- No published retail carry track record net of fees on Binance exists; the only institutional benchmark (1Token) is chart-only.
