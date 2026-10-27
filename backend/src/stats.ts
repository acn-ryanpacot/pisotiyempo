import type { MarketStats } from "./ai/claude";
import type { Bar, Quote } from "./types";

/** Minimum peso turnover for a stock to count as "liquid" in gainers/losers, so ₱50k micro-cap trades don't dominate. */
const LIQUID_VALUE = 5_000_000;

export function computeMarketStats(date: string, quotes: Quote[], psei: Bar[]): MarketStats {
  const today = quotes.filter((q) => q.date === date);
  const breadth = { advancers: 0, decliners: 0, unchanged: 0 };
  for (const q of today) {
    if (q.changePct == null) continue;
    if (q.changePct > 0) breadth.advancers++;
    else if (q.changePct < 0) breadth.decliners++;
    else breadth.unchanged++;
  }
  const withValue = today.map((q) => ({ ...q, value: q.close * (q.volume ?? 0) }));
  const liquid = withValue.filter((q) => q.value >= LIQUID_VALUE && q.changePct != null);
  const byChange = [...liquid].sort((a, b) => b.changePct! - a.changePct!);
  const pick = (q: (typeof liquid)[number]) => ({ symbol: q.symbol, close: q.close, changePct: q.changePct! });

  const sortedPsei = [...psei].sort((a, b) => a.date.localeCompare(b.date));
  const last = sortedPsei.at(-1);
  const prev = sortedPsei.at(-2);
  return {
    date,
    psei: last
      ? { close: last.close, changePct: prev ? Math.round(((last.close - prev.close) / prev.close) * 10000) / 100 : null }
      : null,
    breadth,
    totalValue: Math.round(withValue.reduce((s, q) => s + q.value, 0)),
    mostActive: [...withValue]
      .sort((a, b) => b.value - a.value)
      .slice(0, 10)
      .map((q) => ({ symbol: q.symbol, name: q.name, close: q.close, changePct: q.changePct, value: Math.round(q.value) })),
    topGainers: byChange.filter((q) => q.changePct! > 0).slice(0, 5).map(pick),
    topLosers: byChange.reverse().filter((q) => q.changePct! < 0).slice(0, 5).map(pick),
  };
}
