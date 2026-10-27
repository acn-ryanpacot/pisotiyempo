import { Eodhd } from "./providers/eodhd";
import { PseEdge } from "./providers/pseEdge";
import { phisixSnapshot, yahooPsei } from "./providers/community";
import type { Bar, Disclosure, Dividend, Env, ForeignFlowDay, Listing, Quote, Sourced } from "./types";
import { addDays, firstUsable, phtToday, type Attempt } from "./util";

const HOUR = 3600;

/** Daily snapshot stored in KV so every app install reads one shared copy. */
export interface Snapshot {
  date: string;
  quotes: Quote[];
}

/**
 * The fallback chains live here. Each public method tries EODHD first (when a key is configured)
 * and then the free unofficial sources, and reports which source answered.
 */
export class Market {
  private readonly eodhd: Eodhd | null;
  private readonly pseEdge: PseEdge;

  constructor(private readonly env: Env) {
    this.eodhd = env.EODHD_API_KEY ? new Eodhd(env.EODHD_API_KEY) : null;
    this.pseEdge = new PseEdge(env.CACHE);
  }

  private eodhdAttempt<T>(run: (e: Eodhd) => Promise<T>): Attempt<T>[] {
    const e = this.eodhd;
    return e ? [{ source: "eodhd", run: () => run(e) }] : [];
  }

  /** Latest trading day for every PSE security, with % change filled in from the previous snapshot when missing. */
  async latest(forceRefresh = false): Promise<Sourced<Snapshot>> {
    const previous = await this.env.CACHE.get<Sourced<Snapshot>>("eod:latest", "json");
    if (!forceRefresh && previous && !isStale(previous)) return previous;

    const result = await firstUsable<Quote[]>(
      [
        ...this.eodhdAttempt((e) => e.latestDay()),
        { source: "phisix", run: phisixSnapshot, notes: ["Open, high and low are not available from this source."] },
      ],
      (q) => q.length > 50,
    );
    // phisix stamps its data with today's date even on weekends and holidays, so detect a repeat of the last day.
    const repeat = result.source === "phisix" && previous != null && sameTradingDay(result.data, previous.data.quotes);
    const date = result.source === "phisix" ? lastWeekdayOnOrBefore(mostCommonDate(result.data)) : mostCommonDate(result.data);
    const isNewDay = !repeat && (!previous || date > previous.data.date || (date === previous.data.date && result.source === "eodhd" && previous.source !== "eodhd"));

    if (!isNewDay && previous) {
      // Nothing new upstream yet (e.g. EODHD still publishing, or a holiday). Record the check so the
      // next refresh waits an hour; KV's free tier only allows 1,000 writes a day.
      const checked = { ...previous, fetchedAt: new Date().toISOString() };
      await this.env.CACHE.put("eod:latest", JSON.stringify(checked));
      return checked;
    }
    const prevQuotes = previous && previous.data.date < date ? previous.data.quotes : [];
    const quotes = fillChange(result.data, prevQuotes).map((q) => (result.source === "phisix" ? { ...q, date } : q));
    const sourced: Sourced<Snapshot> = { ...result, data: { date, quotes } };
    if (previous && previous.data.date < date) await this.env.CACHE.put("eod:previous", JSON.stringify(previous));
    await this.env.CACHE.put("eod:latest", JSON.stringify(sourced));
    return sourced;
  }

  async listings(): Promise<Sourced<Listing[]>> {
    const cached = await this.env.CACHE.get<Sourced<Listing[]>>("listings", "json");
    if (cached && Date.now() - Date.parse(cached.fetchedAt) < 7 * 24 * HOUR * 1000) return cached;
    const result = await firstUsable<Listing[]>(
      [
        ...this.eodhdAttempt((e) => e.listings()),
        {
          source: "phisix",
          run: async () => (await phisixSnapshot()).map((q) => ({ symbol: q.symbol, name: q.name ?? q.symbol, type: null })),
        },
      ],
      (l) => l.length > 50,
    );
    await this.env.CACHE.put("listings", JSON.stringify(result));
    return result;
  }

  async history(symbol: string, from: string): Promise<Sourced<Bar[]>> {
    return cached(`history:${symbol}:${from}`, 6 * HOUR, () =>
      firstUsable<Bar[]>(
        [
          ...this.eodhdAttempt((e) => e.history(symbol, from)),
          {
            source: "pse-edge",
            run: () => this.pseEdge.history(symbol, from, phtToday()),
            notes: ["Volume is estimated from peso turnover."],
          },
        ],
        (b) => b.length > 0,
      ),
    );
  }

  async psei(from: string): Promise<Sourced<Bar[]>> {
    return cached(`psei:${from}`, 6 * HOUR, () =>
      firstUsable<Bar[]>(
        [
          ...this.eodhdAttempt((e) => e.indexHistory(from)),
          { source: "yahoo", run: yahooPsei, notes: ["This source may only return the most recent day."] },
        ],
        (b) => b.length > 0,
      ),
    );
  }

  async dividends(symbol: string): Promise<Sourced<Dividend[]>> {
    const from = addDays(phtToday(), -3 * 365);
    return cached(`dividends:${symbol}`, 12 * HOUR, () =>
      firstUsable<Dividend[]>(
        [...this.eodhdAttempt((e) => e.dividends(symbol, from)), { source: "pse-edge", run: () => this.pseEdge.dividends(symbol) }],
        // An empty list from EODHD may just mean no coverage, so let PSE Edge have a go.
        (d) => d.length > 0,
      ).catch(() => ({ source: "pse-edge" as const, fetchedAt: new Date().toISOString(), data: [] as Dividend[] })),
    );
  }

  /** Disclosures only exist on PSE Edge; there is no EODHD equivalent on the planned tier. */
  async disclosures(symbol: string): Promise<Sourced<Disclosure[]>> {
    return cached(`disclosures:${symbol}`, 1 * HOUR, () =>
      firstUsable<Disclosure[]>([{ source: "pse-edge", run: () => this.pseEdge.disclosures(symbol) }], () => true),
    );
  }

  disclosureText(id: string): Promise<string> {
    return this.pseEdge.disclosureText(id);
  }

  /**
   * Foreign buying/selling has no free or EODHD source. PSE publishes it in its daily reports, whose terms
   * forbid reproduction without consent, so the data is uploaded through the admin endpoint from a
   * licensed feed. Returns null when nothing has been uploaded.
   */
  async foreignFlow(): Promise<Sourced<ForeignFlowDay> | null> {
    return this.env.CACHE.get<Sourced<ForeignFlowDay>>("foreign-flow:latest", "json");
  }

  async putForeignFlow(day: ForeignFlowDay): Promise<void> {
    const sourced: Sourced<ForeignFlowDay> = { source: "manual", fetchedAt: new Date().toISOString(), data: day };
    await this.env.CACHE.put("foreign-flow:latest", JSON.stringify(sourced));
  }
}

/** Short-lived cache for per-symbol data, using the Workers Cache API (free and not subject to KV write limits). */
async function cached<T>(key: string, ttlSeconds: number, load: () => Promise<T>): Promise<T> {
  const cache = (caches as unknown as { default: Cache }).default;
  const req = new Request(`https://cache.piso-tiyempo.internal/${encodeURIComponent(key)}`);
  const hit = await cache.match(req);
  if (hit) return (await hit.json()) as T;
  const value = await load();
  await cache.put(
    req,
    new Response(JSON.stringify(value), { headers: { "Content-Type": "application/json", "Cache-Control": `max-age=${ttlSeconds}` } }),
  );
  return value;
}

export function mostCommonDate(quotes: Quote[]): string {
  const counts = new Map<string, number>();
  for (const q of quotes) counts.set(q.date, (counts.get(q.date) ?? 0) + 1);
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || b[0].localeCompare(a[0]))[0]?.[0] ?? phtToday();
}

export function fillChange(quotes: Quote[], previous: Quote[]): Quote[] {
  const prevClose = new Map(previous.map((q) => [q.symbol, q.close]));
  return quotes.map((q) => {
    if (q.changePct != null) return q;
    const prev = prevClose.get(q.symbol);
    return prev ? { ...q, changePct: Math.round(((q.close - prev) / prev) * 10000) / 100 } : q;
  });
}

export function lastWeekdayOnOrBefore(date: string): string {
  let d = date;
  while ([0, 6].includes(new Date(`${d}T00:00:00Z`).getUTCDay())) d = addDays(d, -1);
  return d;
}

/** True when at least 95% of symbols present in both lists have the same close and volume. */
export function sameTradingDay(a: Quote[], b: Quote[]): boolean {
  const prev = new Map(b.map((q) => [q.symbol, q]));
  let both = 0;
  let same = 0;
  for (const q of a) {
    const p = prev.get(q.symbol);
    if (!p) continue;
    both++;
    if (p.close === q.close && p.volume === q.volume) same++;
  }
  return both > 0 && same / both >= 0.95;
}

/**
 * A snapshot is stale once a newer trading day should exist: after 4 PM PHT on a weekday whose data
 * we don't have yet, re-check at most once an hour.
 */
export function isStale(snapshot: Sourced<Snapshot>, now: Date = new Date()): boolean {
  const today = phtToday(now);
  const expected = Number(new Date(now.getTime() + 8 * HOUR * 1000).getUTCHours()) >= 16 ? lastWeekdayOnOrBefore(today) : lastWeekdayOnOrBefore(addDays(today, -1));
  if (snapshot.data.date >= expected) return false;
  return now.getTime() - Date.parse(snapshot.fetchedAt) > HOUR * 1000;
}
