import { describe, expect, it } from "vitest";
import { parseLang } from "../src/ai/claude";
import { parseJournalInput } from "../src/ai/journal";
import { ValidationError } from "../src/ai/schemas";

describe("language", () => {
  it("accepts only supported languages", () => {
    expect(parseLang("fil")).toBe("fil");
    expect(parseLang("taglish")).toBe("taglish");
    expect(parseLang("FIL")).toBe("en");
    expect(parseLang(undefined)).toBe("en");
  });
});

describe("journal review input", () => {
  it("trims long text and caps list sizes", () => {
    const input = parseJournalInput({
      entries: Array.from({ length: 100 }, () => ({ date: "2026-11-01", symbol: "jfc", setup: "Breakout", emotion: "Calm", mistakes: ["a", "b"], rating: 4, reason: "x".repeat(2000), lesson: "" })),
      closed: [{ symbol: "JFC", date: "2026-11-05", pnlPct: 3.2, holdingDays: 4 }],
    });
    expect(input.entries).toHaveLength(40);
    expect(input.entries[0]!.symbol).toBe("JFC");
    expect(input.entries[0]!.reason).toHaveLength(400);
    expect(input.closed[0]).toEqual({ symbol: "JFC", date: "2026-11-05", pnlPct: 3.2, holdingDays: 4 });
  });

  it("rejects an empty review", () => {
    expect(() => parseJournalInput({ entries: [], closed: [] })).toThrow(ValidationError);
  });
});
