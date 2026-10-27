// JSON schemas for Claude structured outputs, plus validators for what the app sends in.

export const STANCES = ["STRONG_BUY", "BUY", "HOLD", "REDUCE", "SELL"] as const;

export const stockAnalysisSchema = {
  type: "object",
  additionalProperties: false,
  required: ["headline", "summary", "bullCase", "bearCase", "keyLevels", "riskNote", "stance", "confidence", "oneLiner"],
  properties: {
    headline: { type: "string", description: "Max 12 words." },
    oneLiner: { type: "string", description: "One sentence, max 20 words, used as the reason on the dashboard list." },
    summary: { type: "string", description: "2-4 sentences in plain language." },
    bullCase: { type: "array", items: { type: "string" } },
    bearCase: { type: "array", items: { type: "string" } },
    keyLevels: { type: "string", description: "Support, resistance and invalidation levels in pesos." },
    riskNote: { type: "string" },
    stance: { type: "string", enum: [...STANCES] },
    confidence: { type: "integer", description: "0-100" },
  },
} as const;

export interface StockAnalysis {
  headline: string;
  oneLiner: string;
  summary: string;
  bullCase: string[];
  bearCase: string[];
  keyLevels: string;
  riskNote: string;
  stance: (typeof STANCES)[number];
  confidence: number;
}

export const marketBriefSchema = {
  type: "object",
  additionalProperties: false,
  required: ["headline", "mood", "overview", "highlights", "risks", "watchTomorrow"],
  properties: {
    headline: { type: "string", description: "Max 12 words." },
    mood: { type: "string", enum: ["BULLISH", "NEUTRAL", "BEARISH"] },
    overview: { type: "string", description: "3-5 sentences." },
    highlights: { type: "array", items: { type: "string" } },
    risks: { type: "array", items: { type: "string" } },
    watchTomorrow: { type: "array", items: { type: "string" } },
  },
} as const;

export interface MarketBrief {
  headline: string;
  mood: "BULLISH" | "NEUTRAL" | "BEARISH";
  overview: string;
  highlights: string[];
  risks: string[];
  watchTomorrow: string[];
}

export const disclosureSummarySchema = {
  type: "object",
  additionalProperties: false,
  required: ["summary", "impact", "keyPoints"],
  properties: {
    summary: { type: "string", description: "2-3 sentences." },
    impact: { type: "string", enum: ["POSITIVE", "NEUTRAL", "NEGATIVE"] },
    keyPoints: { type: "array", items: { type: "string" } },
  },
} as const;

export interface DisclosureSummary {
  summary: string;
  impact: "POSITIVE" | "NEUTRAL" | "NEGATIVE";
  keyPoints: string[];
}

/**
 * Technical features the app's on-device engine computes for one stock. The backend only turns them
 * into prose; it never trusts them for anything else.
 */
export interface StockFeatures {
  symbol: string;
  name: string;
  asOf: string;
  close: number;
  changePct: number | null;
  score: number;
  indicators: Record<string, number | null>;
  patterns: Array<{ name: string; direction: string; confidence: number; detail: string }>;
  levels: { support: number[]; resistance: number[] };
  backtest: Array<{ signal: string; winRate: number; samples: number; avgReturnPct: number }>;
  signals: string[];
}

export class ValidationError extends Error {}

function isNum(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

function str(v: unknown, max: number, field: string): string {
  if (typeof v !== "string" || v.length > max) throw new ValidationError(`${field} must be a string up to ${max} chars`);
  return v;
}

/** Validates and trims the payload so a client can't push arbitrary text or huge prompts through the AI endpoints. */
export function parseFeatures(body: unknown): StockFeatures {
  if (typeof body !== "object" || body === null) throw new ValidationError("body must be an object");
  const b = body as Record<string, unknown>;
  const symbol = str(b.symbol, 8, "symbol").toUpperCase();
  if (!/^[A-Z0-9]{1,8}$/.test(symbol)) throw new ValidationError("symbol is invalid");
  if (!isNum(b.close) || b.close <= 0) throw new ValidationError("close must be a positive number");
  if (!isNum(b.score)) throw new ValidationError("score must be a number");

  const indicators: Record<string, number | null> = {};
  for (const [k, v] of Object.entries((b.indicators as Record<string, unknown>) ?? {}).slice(0, 30)) {
    if (/^[a-zA-Z0-9_]{1,24}$/.test(k)) indicators[k] = isNum(v) ? Math.round(v * 10000) / 10000 : null;
  }
  const patterns = (Array.isArray(b.patterns) ? b.patterns : []).slice(0, 12).map((p, i) => {
    const o = p as Record<string, unknown>;
    return {
      name: str(o.name, 60, `patterns[${i}].name`),
      direction: str(o.direction, 12, `patterns[${i}].direction`),
      confidence: isNum(o.confidence) ? o.confidence : 0,
      detail: str(o.detail ?? "", 200, `patterns[${i}].detail`),
    };
  });
  const nums = (v: unknown) => (Array.isArray(v) ? v.filter(isNum).slice(0, 5) : []);
  const levels = (b.levels as Record<string, unknown>) ?? {};
  const backtest = (Array.isArray(b.backtest) ? b.backtest : []).slice(0, 12).map((x, i) => {
    const o = x as Record<string, unknown>;
    return {
      signal: str(o.signal, 60, `backtest[${i}].signal`),
      winRate: isNum(o.winRate) ? o.winRate : 0,
      samples: isNum(o.samples) ? o.samples : 0,
      avgReturnPct: isNum(o.avgReturnPct) ? o.avgReturnPct : 0,
    };
  });
  const signals = (Array.isArray(b.signals) ? b.signals : []).slice(0, 15).map((s, i) => str(s, 120, `signals[${i}]`));

  return {
    symbol,
    name: str(b.name ?? symbol, 120, "name"),
    asOf: str(b.asOf, 10, "asOf"),
    close: b.close,
    changePct: isNum(b.changePct) ? b.changePct : null,
    score: b.score,
    indicators,
    patterns,
    levels: { support: nums(levels.support), resistance: nums(levels.resistance) },
    backtest,
    signals,
  };
}
