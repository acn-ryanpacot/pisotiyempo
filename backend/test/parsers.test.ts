import { describe, expect, it } from "vitest";
import { adjustBars } from "../src/providers/eodhd";
import { mapChart, parseDisclosures, parseDividends, parseSecurityOptions } from "../src/providers/pseEdge";
import { mapPhisix, mapYahoo } from "../src/providers/community";
import { fillChange, isStale, lastWeekdayOnOrBefore, sameTradingDay } from "../src/market";
import { computeMarketStats } from "../src/stats";
import { parseFeatures, ValidationError } from "../src/ai/schemas";
import { firstUsable, parsePseDate, parsePseDateTime, redact } from "../src/util";
import type { Quote } from "../src/types";

// Fixtures below are trimmed copies of real PSE Edge responses captured on 2026-10-25.

describe("PSE Edge parsers", () => {
  it("parses security options from stockData.do", () => {
    const html = `<select name="security_id" id="security_id"><option value="158" selected>JFC</option>
      <option value="672">JFCPA</option><option value="673">JFCPB</option></select>`;
    expect(parseSecurityOptions(html)).toEqual([
      { id: "158", symbol: "JFC" },
      { id: "672", symbol: "JFCPA" },
      { id: "673", symbol: "JFCPB" },
    ]);
  });

  it("maps chart rows and estimates volume from turnover", () => {
    const bars = mapChart([
      { OPEN: 181, VALUE: 200_000_000, CLOSE: 182, CHART_DATE: "Jan 05, 2026 00:00:00", HIGH: 183, LOW: 180 },
      { OPEN: 180, VALUE: 134648559, CLOSE: 183.4, CHART_DATE: "Jan 02, 2026 00:00:00", HIGH: 184.5, LOW: 179 },
    ]);
    expect(bars.map((b) => b.date)).toEqual(["2026-01-02", "2026-01-05"]);
    expect(bars[0]!.volume).toBe(Math.round(134648559 / ((184.5 + 179 + 183.4) / 3)));
  });

  it("parses the company disclosures table", () => {
    const html = `<tbody>
  <tr>
      <td><a href="#viewer" onclick="openPopup('3b7f7ff0c4c9f36dabca0fa0c5b4e4d0');return false;">Declaration of Cash Dividends</a></td>
      <td class="alignC">Nov 11, 2024 03:25 PM</td>
      <td class="alignC">6-1</td>
      <td class="alignC">
      C07163-2024</td>
    </tr></tbody>`;
    expect(parseDisclosures(html, "JFC")).toEqual([
      {
        id: "3b7f7ff0c4c9f36dabca0fa0c5b4e4d0",
        symbol: "JFC",
        title: "Declaration of Cash Dividends",
        announcedAt: "2024-11-11T15:25:00+08:00",
        formNumber: "6-1",
        circular: "C07163-2024",
        url: "https://edge.pse.com.ph/openDiscViewer.do?edge_no=3b7f7ff0c4c9f36dabca0fa0c5b4e4d0",
      },
    ]);
  });

  it("parses the dividends table and keeps only the requested security", () => {
    const html = `<table><tr><th>Type of Security</th><th>Type of Dividend</th><th>Dividend Rate</th><th>Ex-Dividend Date</th><th>Record Date</th><th>Payment Date</th><th>Circular Number</th></tr>
      <tr><td>JFCPB</td><td>Cash</td><td>Php10.60125 per share for Series B preferred shares (JFCPB)</td><td>Sep 24, 2026</td><td>Sep 25, 2026</td><td>Oct 14, 2026</td><td>C06761-2026</td></tr>
      <tr><td>COMMON</td><td>Cash</td><td>Php1.33</td><td>May 04, 2026</td><td>May 5, 2026</td><td>May 20, 2026</td><td>C04480-2026</td></tr>
      <tr><td>COMMON</td><td>Stock</td><td>10%</td><td>Mar 3, 2026</td><td>Mar 4, 2026</td><td>Mar 20, 2026</td><td>C01000-2026</td></tr></table>`;
    const common = parseDividends(html, "JFC", true);
    expect(common).toHaveLength(2);
    expect(common[0]).toMatchObject({ type: "CASH", amount: 1.33, exDate: "2026-05-04", recordDate: "2026-05-05", paymentDate: "2026-05-20" });
    expect(common[1]).toMatchObject({ type: "STOCK", amount: null, rateText: "10%" });

    const preferred = parseDividends(html, "JFCPB", false);
    expect(preferred).toHaveLength(1);
    expect(preferred[0]).toMatchObject({ amount: 10.60125, exDate: "2026-09-24" });
  });
});

describe("community sources", () => {
  it("maps phisix, restoring quotes in names", () => {
    const quotes = mapPhisix({
      as_of: "2026-10-23T00:00:00+08:00",
      stocks: [{ name: 'Ayala Class ``B``', price: { currency: "PHP", amount: 1900 }, percentChange: 0.53, volume: 35, symbol: "acpb4" }],
    });
    expect(quotes[0]).toMatchObject({ symbol: "ACPB4", name: 'Ayala Class "B"', date: "2026-10-23", close: 1900, open: null, changePct: 0.53 });
  });

  it("maps yahoo and skips null closes", () => {
    const bars = mapYahoo({
      chart: { result: [{ timestamp: [1792800000, 1792886400], indicators: { quote: [{ open: [1, null], high: [2, null], low: [0.5, null], close: [1.5, null], volume: [10, null] }] } }] },
    });
    expect(bars).toHaveLength(1);
  });
});

describe("EODHD", () => {
  it("adjusts OHLC by the adjusted close ratio", () => {
    const [bar] = adjustBars([{ date: "2026-01-02", open: 100, high: 110, low: 90, close: 100, adjusted_close: 50, volume: 1 }]);
    expect(bar).toMatchObject({ open: 50, high: 55, low: 45, close: 50 });
  });
});

describe("market helpers", () => {
  const q = (symbol: string, close: number, changePct: number | null, volume = 1_000_000): Quote => ({
    symbol, name: null, date: "2026-10-23", open: null, high: null, low: null, close, volume, changePct,
  });

  it("fills change from the previous snapshot only when missing", () => {
    const out = fillChange([q("A", 110, null), q("B", 50, 1.5)], [q("A", 100, null), q("B", 40, null)]);
    expect(out[0]!.changePct).toBe(10);
    expect(out[1]!.changePct).toBe(1.5);
  });

  it("detects a repeated trading day", () => {
    expect(sameTradingDay([q("A", 1, 0), q("B", 2, 0)], [q("A", 1, 0), q("B", 2, 0)])).toBe(true);
    expect(sameTradingDay([q("A", 1.1, 0), q("B", 2, 0)], [q("A", 1, 0), q("B", 2, 0)])).toBe(false);
  });

  it("rolls weekends back to Friday", () => {
    expect(lastWeekdayOnOrBefore("2026-10-25")).toBe("2026-10-23");
    expect(lastWeekdayOnOrBefore("2026-10-23")).toBe("2026-10-23");
  });

  it("marks a snapshot stale only after the next close and an hour since the last check", () => {
    const snap = (date: string, fetchedAt: string) => ({ source: "eodhd" as const, fetchedAt, data: { date, quotes: [] } });
    // Monday 2026-10-26 at 17:00 PHT = 09:00 UTC.
    const now = new Date("2026-10-26T09:00:00Z");
    expect(isStale(snap("2026-10-23", "2026-10-26T07:30:00Z"), now)).toBe(true);
    expect(isStale(snap("2026-10-23", "2026-10-26T08:30:00Z"), now)).toBe(false);
    expect(isStale(snap("2026-10-26", "2026-10-26T07:30:00Z"), now)).toBe(false);
    // Monday 10:00 PHT: Friday's data is current.
    expect(isStale(snap("2026-10-23", "2026-10-24T00:00:00Z"), new Date("2026-10-26T02:00:00Z"))).toBe(false);
  });

  it("computes breadth and liquid movers", () => {
    const stats = computeMarketStats("2026-10-23", [q("A", 10, 5, 1_000_000), q("B", 10, -3, 1_000_000), q("C", 1, 20, 100), q("D", 5, 0)], [
      { date: "2026-10-22", open: null, high: null, low: null, close: 6000, volume: null },
      { date: "2026-10-23", open: null, high: null, low: null, close: 6060, volume: null },
    ]);
    expect(stats.breadth).toEqual({ advancers: 2, decliners: 1, unchanged: 1 });
    expect(stats.topGainers.map((g) => g.symbol)).toEqual(["A"]);
    expect(stats.topLosers.map((g) => g.symbol)).toEqual(["B"]);
    expect(stats.psei).toEqual({ close: 6060, changePct: 1 });
  });
});

describe("util", () => {
  it("parses PSE dates", () => {
    expect(parsePseDate("Jul 2, 2026")).toBe("2026-07-02");
    expect(parsePseDateTime("Oct 21, 2026 12:15 PM")).toBe("2026-10-21T12:15:00+08:00");
    expect(parsePseDateTime("Oct 21, 2026 12:15 AM")).toBe("2026-10-21T00:15:00+08:00");
  });

  it("redacts api tokens", () => {
    expect(redact("https://x/api?api_token=secret&fmt=json")).toBe("https://x/api?api_token=***&fmt=json");
  });

  it("falls back to the next source when the first fails or is empty", async () => {
    const result = await firstUsable<number[]>(
      [
        { source: "eodhd", run: async () => { throw new Error("down"); } },
        { source: "phisix", run: async () => [] },
        { source: "pse-edge", run: async () => [1] },
      ],
      (v) => v.length > 0,
      () => {},
    );
    expect(result.source).toBe("pse-edge");
  });
});

describe("feature validation", () => {
  const valid = { symbol: "jfc", name: "Jollibee", asOf: "2026-10-23", close: 143.5, score: 42, indicators: { rsi14: 55.123456 }, patterns: [], levels: { support: [140], resistance: [150] }, backtest: [], signals: [] };

  it("accepts and normalizes a valid payload", () => {
    const f = parseFeatures(valid);
    expect(f.symbol).toBe("JFC");
    expect(f.indicators.rsi14).toBe(55.1235);
  });

  it("rejects bad symbols and oversized text", () => {
    expect(() => parseFeatures({ ...valid, symbol: "J F C" })).toThrow(ValidationError);
    expect(() => parseFeatures({ ...valid, signals: ["x".repeat(500)] })).toThrow(ValidationError);
  });
});
