export interface Env {
  CACHE: KVNamespace;
  AI_ENABLED?: string;
  EODHD_API_KEY?: string;
  ANTHROPIC_API_KEY?: string;
  APP_KEY?: string;
  ADMIN_KEY?: string;
}

export type Source = "eodhd" | "pse-edge" | "phisix" | "yahoo" | "manual";

/** One trading day. Dates are ISO `YYYY-MM-DD` in Philippine time. */
export interface Bar {
  date: string;
  open: number | null;
  high: number | null;
  low: number | null;
  close: number;
  volume: number | null;
}

export interface Quote extends Bar {
  symbol: string;
  name: string | null;
  /** Percent change from the previous close, when the source provides or we can derive it. */
  changePct: number | null;
}

export interface Listing {
  symbol: string;
  name: string;
  type: string | null;
}

export interface Dividend {
  symbol: string;
  type: "CASH" | "STOCK" | "PROPERTY" | "OTHER";
  /** Peso amount per share for cash dividends; null when only a text rate is available. */
  amount: number | null;
  rateText: string;
  exDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
}

export interface Disclosure {
  id: string;
  symbol: string;
  title: string;
  /** ISO date-time in Philippine time, e.g. 2026-10-21T12:15:00+08:00 */
  announcedAt: string;
  formNumber: string;
  circular: string;
  url: string;
}

export interface ForeignFlowDay {
  date: string;
  buyValue: number;
  sellValue: number;
  netValue: number;
  bySymbol: Array<{ symbol: string; netValue: number }>;
}

/** Every data response says where the data came from so the app can show it and warn on degraded sources. */
export interface Sourced<T> {
  source: Source;
  fetchedAt: string;
  /** Set when the source could not provide everything, e.g. volume estimated from turnover. */
  notes?: string[];
  data: T;
}
