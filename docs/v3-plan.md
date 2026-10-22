<!-- Proposal written 2026-10-22, simplified the same day at the owner's request: the app keeps paper money and
     never places orders; the owner copies calls on Binance by hand. Approved by the owner on 2026-10-22 ("approve"); built on branch v3.
     The research behind it: docs/research-2026-10/ (report.md and six briefs). -->

# Signal Lab 3: better calls to copy by hand

## What you asked, and what the research found

You asked for wilder strategies, the ones crypto traders use to make money, so the app's calls are worth
copying on Binance. Six research passes on 2026-10-22 read about 350 sources and pulled Binance's own
funding history. In plain words:

- **The tricks people post about do not work at this app's speed.** Buying new listings, chasing the Upbit
  pump, copying whales, copy trading, prediction markets, grid bots, arbitrage, machine learning on charts,
  Fear and Greed, CME gaps, betting against extreme funding, covered calls: every one was tested by someone
  and lost or broke even after fees. The gains people screenshot happen in the first minutes and are gone
  by the next hourly close.
- **The biggest gain found is in how much you buy, not what.** Sizing by how wild the market is raised a
  trend strategy's result from Sharpe 0.65 to 1.83 and cut its worst fall from 80% to 15%, in two separate
  tests after fees. No entry rule anywhere comes close.
- **Two things from the futures market help.** When funding has been negative for weeks (shorts paying
  longs), Bitcoin's next one to three months were strong in every episode since 2018. When funding and the
  futures premium run hot, a crash is more likely: both were screaming the week before 10 October 2025.
- **Events are for avoiding, not chasing.** New coins keep falling for six months. A delisting notice comes
  3 to 14 days before a 15 to 57% drop. Big token unlocks are followed by falls nine times in ten.
- **The app's ledger already works.** Hourly trend made 121 calls in five days: 10% winners, behind random
  entries, past the app's own Failing line. The method is right; it needs better inputs.

## What changes, in four parts

### 1. Every call tells you how much to buy

Today a call says coin, entry and stop, sized against a 10,000 paper account. After this plan:

- You set **"money I trade with"** in Settings (your real Binance balance, in USDT). Every call shows the
  amount in USDT and in coins, rounded to what Binance accepts for that coin, so you can type it straight
  into the order screen.
- The amount is **smaller when the market is wild** (the paper book is held to a steady level of risk),
  **smaller when you already hold several coins that move together** (most alts move with Bitcoin), and
  **zero when leverage is overheated** (part 2). Trend calls get a little bigger when the trend is strong.
- The ledger keeps the old fixed size next to the new one, so after a year it can say whether the new
  sizing helped. If it did not, it goes.

### 2. The app stands aside from the known losers

- **New coins**: no calls on coins listed less than 180 days ago (90 today).
- **Delisting notices**: the app reads Binance's and Upbit's announcement pages daily; a coin named in a
  delisting notice gets no new calls, and an open call on it is closed at the next open with a "sell now"
  notification.
- **Overheated leverage**: when Bitcoin's weekly funding is above 20% a year and the quarterly futures
  premium above 15% a year, or implied volatility jumps 15 points in a week, new calls are halved or
  skipped. Skipped calls are still recorded, so the ledger can say whether skipping helped.
- Token unlocks would be the fourth veto, but no free unlock calendar exists any more. It waits.

### 3. Two new strategies, judged like the others

| Strategy | What it does | Why | Starts as |
|---|---|---|---|
| **Hated rally** | Buys Bitcoin, Ether and the ten busiest coins when funding has been negative for a month, or very negative for a week. Holds up to 60 days, leaves on a close below the 20-day low, safety stop 3 ATR | Every such episode since 2018 was followed by a strong run (K33, VanEck); no one has published the method, so it must prove itself here | Testing: no calls on Today until it earns them |
| **Trend leaders** | Every Monday, ranks the 100 largest coins by a bundle of 28 trend indicators from a 2025 Journal of Financial Quantitative Analysis paper and buys the top fifth for a week | Peer-reviewed; 3.1 to 3.4% a week after fees on the paper's sample, which ends in 2022, so it may have faded | Watch: it replaces Leaders if it beats it on the same coins |

Both are registered before their first backtest and compared with random entries at normal and double
fees, exactly like the seven existing strategies. Hourly trend is switched off by its Failing tier.

### 4. Copying is tracked

- Each call card gets **"I took it"** with a box for your real buy price, and each exit **"I sold"** with
  your real sell price. Optional; skip it and nothing changes.
- The ledger gets a **"My copies"** view: your real results next to the paper results, and how far your
  fills sat from the paper entry at the daily open (00:00 UTC, which is 02:00 or 03:00 in Cairo). If your
  morning fills cost you 1% against the paper price, the app will show it, and we can then decide whether
  the paper entry should move to the hour you actually trade.
- Notifications already say "SOL long: entry 142.30, stop 136.10" and "Exit SOL: stop hit"; they gain the
  amount to buy and the words "sell now" on exits.

## New data the phone reads (no account, no key)

Checked from your network on 2026-10-22: funding for every coin in the universe (today only BTC and ETH),
the quarterly futures premium and the positioning ratios from Binance's public futures data, the Bitcoin
and Ether implied-volatility index from Deribit, and the Binance and Upbit announcement pages. Binance's
website refused connections from the PC while its API answered, so the announcement feed is tested on the
phone first; the fallback is the coin's trading status, which only changes at the halt. Binance keeps 30
days of futures data, so the phone stores it from the first run on and includes it in backup and sync.

## Build order (branch `v3`; each milestone committed when its tests pass; never pushed unasked)

| # | Lands | You get |
|---|---|---|
| 1 (2.2.0) | Research into docs; 180-day rule; Hourly trend off; the new data feeds; market line shows funding, premium and volatility | A richer Today, nothing to learn |
| 2 (2.3.0) | Sizing (part 1) with both sizes recorded; the three vetoes (part 2); "money I trade with" | Calls that say how much, and fewer bad ones |
| 3 (2.4.0) | Hated rally and Trend leaders (part 3); "I took it" and "My copies" (part 4); bigger notifications | Two new strategies on the Strategies screen, your copies tracked |

Each milestone is built and unit-tested on the PC, then installed on the Fold7 when you say so, as before.

## What you decide

1. **"approve"** on this plan before any source changes. Then say whether milestones 1 to 3 may run back
   to back without a new "approve" each, as milestones 2 to 9 of Signal Lab 2 did.
2. When to install each version on the phone.
3. Nothing else. The app never places an order, holds no exchange key, and never asks for one.

## Not in this plan

Real orders from the app (your decision: never). Short positions, funding carry and pairs trading, which
need futures. A paid unlock calendar. A second exchange. Any strategy without a published or in-house test.

## Questions the ledger will answer

- Does the new sizing improve the paper book, or only cut its falls?
- Does the negative-funding rule hold on the app's own data after fees?
- Does skipping overheated weeks skip more losers than winners?
- Does Trend leaders beat Leaders on the same coins after 2022?
- How far are your real fills from the paper entry, and is the 0.40% cost assumption right?
