import type { Bar, Quote } from "../types";
import { fetchOk } from "../util";

// Free community / unofficial sources used only when EODHD is unavailable.

interface PhisixStock {
  name: string;
  price: { currency: string; amount: number };
  percentChange: number;
  volume: number;
  symbol: string;
}

/** phisix gives last price, % change and volume for every PSE security, but no open/high/low. */
export function mapPhisix(json: { stocks: PhisixStock[]; as_of: string }): Quote[] {
  const date = json.as_of.slice(0, 10);
  return json.stocks
    .filter((s) => s.price.amount > 0)
    .map((s) => ({
      symbol: s.symbol.toUpperCase(),
      name: s.name.replace(/``/g, '"'),
      date,
      open: null,
      high: null,
      low: null,
      close: s.price.amount,
      volume: s.volume,
      changePct: s.percentChange,
    }));
}

export async function phisixSnapshot(): Promise<Quote[]> {
  const res = await fetchOk("phisix", "https://phisix-api3.appspot.com/stocks.json");
  return mapPhisix((await res.json()) as { stocks: PhisixStock[]; as_of: string });
}

interface YahooChart {
  chart: {
    result: Array<{
      timestamp?: number[];
      indicators: { quote: Array<{ open: (number | null)[]; high: (number | null)[]; low: (number | null)[]; close: (number | null)[]; volume: (number | null)[] }> };
    }> | null;
  };
}

export function mapYahoo(json: YahooChart): Bar[] {
  const r = json.chart.result?.[0];
  const q = r?.indicators.quote[0];
  if (!r?.timestamp || !q) return [];
  const bars: Bar[] = [];
  r.timestamp.forEach((ts, i) => {
    const close = q.close[i];
    if (close == null) return;
    bars.push({
      date: new Date((ts + 8 * 3600) * 1000).toISOString().slice(0, 10),
      open: q.open[i] ?? null,
      high: q.high[i] ?? null,
      low: q.low[i] ?? null,
      close,
      volume: q.volume[i] ?? null,
    });
  });
  return bars;
}

/** Yahoo carries the PSEi (but not individual PSE stocks). It often returns only the latest bar. */
export async function yahooPsei(): Promise<Bar[]> {
  const res = await fetchOk("yahoo", "https://query1.finance.yahoo.com/v8/finance/chart/PSEI.PS?range=2y&interval=1d");
  return mapYahoo((await res.json()) as YahooChart);
}
