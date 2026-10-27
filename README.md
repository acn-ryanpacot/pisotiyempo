# Piso Tiyempo

**Buy right. Sell right. Tamang tiyempo.**

An Android app for Philippine Stock Exchange (PSE) investors: daily prices, charts for every stock, basic and advanced pattern detection, AI analysis, and a dashboard with your portfolio value plus the top 5 buy and top 5 sell ideas.

```
┌──────────────── Android app (Kotlin, Jetpack Compose) ────────────────┐
│ Dashboard · Market · Portfolio · Watchlist/Alerts/Disclosures · Tools  │
│ On-device engine: indicators, candlestick + chart patterns, scoring,  │
│ backtests, PH fees/board lots, rules-based fallback writer            │
│ Room database (works offline) · WorkManager daily sync ~6 PM PHT      │
└──────────────┬────────────────────────────────────────────────────────┘
               │ HTTPS + X-App-Key            fallback if backend is down:
               ▼                              phisix community feed (direct)
┌──────── Backend: Cloudflare Worker (free tier) ────────┐
│ Market data:  EODHD ──► PSE Edge / phisix / Yahoo       │
│ AI:           Claude Haiku 5.5 (per stock, disclosures) │
│               Claude Opus 5.5 (one daily market brief)  │
│ Keys live here as secrets, never in the APK             │
└─────────────────────────────────────────────────────────┘
```

## Features (v1)

| Area | What it does |
|---|---|
| Daily data | Latest close for every PSE security, plus 2 years of history for the 120 most-traded stocks and everything you own or watch. |
| Charts | Candlesticks with volume; SMA 20/50/200 and Bollinger overlays; pattern arrows; support/resistance lines; 1M to 2Y ranges. |
| Basic patterns | 17 candlestick patterns (hammer, engulfing, morning/evening star, three soldiers/crows, doji family…), MA golden/death cross, MACD and RSI signals, volume surges, 52-week breakouts, Bollinger squeeze. |
| Advanced patterns | Head and shoulders (and inverse), double/triple tops and bottoms, ascending/descending/symmetrical triangles, rising/falling wedges, bull/bear flags and pennants, cup and handle, RSI divergence, Ichimoku, Fibonacci retracements, Wyckoff accumulation/distribution (approximate). |
| Scoring | -100 to +100 from trend, momentum, patterns (weighted by their backtested win rate on that stock), volume, trend strength and relative strength vs the PSEi. |
| AI analysis | Claude writes the per-stock read, the daily market brief and disclosure summaries. When AI is off or unavailable, a rules engine writes the same sections. Every card shows which one wrote it. |
| Dashboard | Portfolio value, day change, unrealized/realized P/L, net dividends, market brief, top 5 buy ideas (liquid stocks only), top 5 sell signals (your holdings and watchlist first). |
| PH trading tools | Fee calculator (commission + VAT, PSE fee, SCCP fee, stock transaction tax), break-even price, board-lot/tick lookup, position sizer that rounds to board lots. |
| Market | PSEi chart, breadth, value-weighted sector moves, top gainers/losers/most active, foreign flow (needs a licensed feed, see below). |
| Disclosures and dividends | PSE Edge disclosures for your stocks with one-tap AI summaries; dividend history and upcoming ex-dates. |
| Backtests | For each signal: how many times it fired on this stock, its win rate and average 10-day move. |
| Alerts | Price above/below, score turns buy/sell, new confirmed pattern. Checked after each daily sync and sent as notifications. |
| Risk | Trade plan (ATR/support stop, target, reward:risk), position sizing, concentration warnings (single stock > 25%, sector > 40%). |

## Data sources and fallbacks

| Data | 1st choice | Fallback(s) | Notes |
|---|---|---|---|
| Latest prices (all stocks) | EODHD bulk end-of-day | phisix community feed → app calls phisix directly if the backend is down | phisix has no open/high/low; candlestick patterns are skipped for those days. |
| Price history | EODHD | PSE Edge chart data | PSE Edge reports peso turnover, so volume is estimated. |
| PSEi | EODHD `PSEI.INDX` | Yahoo `PSEI.PS` | Yahoo only returns the latest day; history builds up in the app. |
| Dividends | EODHD | PSE Edge | |
| Disclosures | PSE Edge | — | |
| Foreign flow | Admin upload from a licensed feed | — | No free or EODHD source exists. |

The fallback sources were verified live on 2026-10-25 (`npm run probe` in `backend/` re-checks them). The EODHD paths follow EODHD's documentation but haven't been called with a real key yet; run `npm run probe -- <EODHD key>` once you have one.

## Running costs

| Item | Cost |
|---|---|
| Cloudflare Workers + KV | Free tier |
| EODHD EOD plan | US$19.99/month (see licensing below) |
| Claude Haiku 5.5 ($0.10 / $0.50 per M tokens) | Results are cached per stock per day and shared by all users, so cost grows with the number of stocks people open, not with users: at most a few hundred calls a day, under US$1/month at typical use. |
| Claude Opus 5.5 ($4 / $20 per M tokens) | One market brief per trading day, written by the cron job: about US$1–2/month. |
| Google Play developer account | US$25 one-time |

## Setup

### 1. Backend (Cloudflare Worker)

```bash
cd backend
npm install
npx wrangler login
npx wrangler kv namespace create CACHE     # paste the id into wrangler.toml
npx wrangler secret put APP_KEY            # any long random string; the app sends it
npx wrangler secret put ADMIN_KEY          # for the foreign-flow upload endpoint
npx wrangler secret put EODHD_API_KEY      # optional: without it, free sources are used
npx wrangler secret put ANTHROPIC_API_KEY  # optional: without it, the app uses its rules engine
npm test && npm run deploy
```

Useful: `npm run dev` for a local server, `npm run probe` to check every data source.

### 2. Android app

1. Install Android Studio and open this folder. Let it create the Gradle wrapper and offer any version upgrades.
2. In `~/.gradle/gradle.properties` (not the repo), set:
   ```properties
   PISO_API_BASE_URL=https://piso-tiyempo-api.<your-subdomain>.workers.dev
   PISO_APP_KEY=<same value as the APP_KEY secret>
   ```
3. Run the `app` configuration on a device or emulator. Unit tests: `gradle :app:testDebugUnitTest`.

CI (`.github/workflows/ci.yml`) runs the backend tests, the Android unit tests and a debug APK build on every push.

## Before publishing on Google Play

See [docs/PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md). The two items that can block a public launch:

1. **Data redistribution rights.** EODHD's personal plans don't permit showing data to other users; a public app needs their commercial licence. PSE data (including PSE Edge and the PSE reports that contain foreign flow) is subject to PSE terms that restrict reproduction. phisix and Yahoo are unofficial. Get written permission or a licensed feed before a public release.
2. **SEC Philippines.** Showing buy/sell ideas to the public can be treated as investment advice. The app positions itself as educational, with a mandatory first-run disclaimer, but have a Philippine securities lawyer review it.

## Project layout

```
app/src/main/java/com/pisotiyempo/
  domain/       Pure Kotlin, unit-tested: indicators, patterns, analysis, PH rules, portfolio math
  data/         Room database, backend client, repositories, settings
  work/         Daily sync worker and notifications
  ui/           Compose screens (dashboard, market, stock, portfolio, watchlist, tools, settings)
app/src/main/assets/chart/   TradingView Lightweight Charts + chart.html
backend/src/    Worker: providers (EODHD, PSE Edge, phisix, Yahoo), fallback chain, Claude endpoints
docs/           Privacy policy, Play Store checklist
```
