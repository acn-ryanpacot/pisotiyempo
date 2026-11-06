import { ValidationError } from "./schemas";

// Journal review: the user's own notes and closed-trade results, sent only when they tap "Get AI coaching".

export const journalReviewSchema = {
  type: "object",
  additionalProperties: false,
  required: ["summary", "strengths", "recurringMistakes", "suggestions", "focusNextWeek"],
  properties: {
    summary: { type: "string", description: "2-3 sentences." },
    strengths: { type: "array", items: { type: "string" } },
    recurringMistakes: { type: "array", items: { type: "string" } },
    suggestions: { type: "array", items: { type: "string" }, description: "Concrete, specific habits to change." },
    focusNextWeek: { type: "string", description: "One sentence: the single most useful thing to work on." },
  },
} as const;

export interface JournalReview {
  summary: string;
  strengths: string[];
  recurringMistakes: string[];
  suggestions: string[];
  focusNextWeek: string;
}

export interface JournalInput {
  entries: Array<{ date: string; symbol: string; setup: string; emotion: string; mistakes: string[]; rating: number | null; reason: string; lesson: string }>;
  closed: Array<{ symbol: string; date: string; pnlPct: number; holdingDays: number }>;
}

const str = (v: unknown, max: number) => (typeof v === "string" ? v.slice(0, max) : "");
const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** Trims the payload to bounded sizes so one request can't send an essay or run up token costs. */
export function parseJournalInput(body: unknown): JournalInput {
  if (typeof body !== "object" || body === null) throw new ValidationError("body must be an object");
  const b = body as Record<string, unknown>;
  const entries = (Array.isArray(b.entries) ? b.entries : []).slice(0, 40).map((e) => {
    const o = (e ?? {}) as Record<string, unknown>;
    return {
      date: str(o.date, 10),
      symbol: str(o.symbol, 8).toUpperCase(),
      setup: str(o.setup, 40),
      emotion: str(o.emotion, 40),
      mistakes: (Array.isArray(o.mistakes) ? o.mistakes : []).slice(0, 8).map((m) => str(m, 40)),
      rating: num(o.rating),
      reason: str(o.reason, 400),
      lesson: str(o.lesson, 400),
    };
  });
  const closed = (Array.isArray(b.closed) ? b.closed : []).slice(0, 60).map((t) => {
    const o = (t ?? {}) as Record<string, unknown>;
    return { symbol: str(o.symbol, 8).toUpperCase(), date: str(o.date, 10), pnlPct: num(o.pnlPct) ?? 0, holdingDays: num(o.holdingDays) ?? 0 };
  });
  if (entries.length === 0 && closed.length === 0) throw new ValidationError("nothing to review");
  return { entries, closed };
}
