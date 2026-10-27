import type { Bar, Dividend, Listing, Quote } from "../types";
import { fetchOk } from "../util";

// EODHD (https://eodhd.com) is the primary, paid source. PSE tickers use the ".PSE" suffix,
// and the PSEi is "PSEI.INDX".
const BASE = "https://eodhd.com/api";

export interface EodhdBar {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  adjusted_close: number;
  volume: number;
}

export interface EodhdBulkRow extends EodhdBar {
  code: string;
  name?: string;
  type?: string;
}

export interface EodhdListing {
  Code: string;
  Name: string;
  Type: string | null;
}

export interface EodhdDividend {
  date: string;
  declarationDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  value: number;
  unadjustedValue: number | null;
  currency: string | null;
}

/**
 * Rescales OHLC by adjusted_close / close so splits and stock dividends don't show up as fake crashes.
 * Indicators and patterns are computed on the adjusted series.
 */
export function adjustBars(rows: EodhdBar[]): Bar[] {
  return rows
    .filter((r) => r.close > 0)
    .map((r) => {
      const f = r.adjusted_close > 0 ? r.adjusted_close / r.close : 1;
      const round = (v: number) => Math.round(v * f * 10000) / 10000;
      return {
        date: r.date,
        open: round(r.open),
        high: round(r.high),
        low: round(r.low),
        close: round(r.close),
        volume: r.volume,
      };
    });
}

export function mapBulk(rows: EodhdBulkRow[]): Quote[] {
  return rows
    .filter((r) => r.close > 0)
    .map((r) => ({
      symbol: r.code,
      name: r.name ?? null,
      date: r.date,
      open: r.open,
      high: r.high,
      low: r.low,
      close: r.close,
      volume: r.volume,
      changePct: null,
    }));
}

export function mapDividends(symbol: string, rows: EodhdDividend[]): Dividend[] {
  return rows.map((r) => ({
    symbol,
    type: "CASH",
    amount: r.unadjustedValue ?? r.value,
    rateText: `${r.currency ?? "PHP"} ${r.unadjustedValue ?? r.value} per share`,
    exDate: r.date,
    recordDate: r.recordDate,
    paymentDate: r.paymentDate,
  }));
}

export class Eodhd {
  constructor(private readonly apiKey: string) {}

  private url(path: string, params: Record<string, string> = {}): string {
    const q = new URLSearchParams({ ...params, api_token: this.apiKey, fmt: "json" });
    return `${BASE}${path}?${q}`;
  }

  async latestDay(): Promise<Quote[]> {
    const res = await fetchOk("eodhd", this.url("/eod-bulk-last-day/PSE", { filter: "extended" }));
    return mapBulk((await res.json()) as EodhdBulkRow[]);
  }

  async listings(): Promise<Listing[]> {
    const res = await fetchOk("eodhd", this.url("/exchange-symbol-list/PSE"));
    const rows = (await res.json()) as EodhdListing[];
    return rows.map((r) => ({ symbol: r.Code, name: r.Name, type: r.Type }));
  }

  async history(symbol: string, from: string): Promise<Bar[]> {
    const res = await fetchOk("eodhd", this.url(`/eod/${symbol}.PSE`, { from, order: "a" }));
    return adjustBars((await res.json()) as EodhdBar[]);
  }

  async indexHistory(from: string): Promise<Bar[]> {
    const res = await fetchOk("eodhd", this.url("/eod/PSEI.INDX", { from, order: "a" }));
    return adjustBars((await res.json()) as EodhdBar[]);
  }

  async dividends(symbol: string, from: string): Promise<Dividend[]> {
    const res = await fetchOk("eodhd", this.url(`/div/${symbol}.PSE`, { from }));
    return mapDividends(symbol, (await res.json()) as EodhdDividend[]);
  }
}
