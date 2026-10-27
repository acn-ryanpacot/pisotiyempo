import type { Bar, Disclosure, Dividend } from "../types";
import { UpstreamError, fetchOk, parsePseDate, parsePseDateTime, stripHtml } from "../util";

// PSE Edge (https://edge.pse.com.ph) is the exchange's public disclosure site. It has no documented API;
// these endpoints are the ones its own pages call. Run `npm run probe` to check they still work.
const BASE = "https://edge.pse.com.ph";

export interface PseEdgeIds {
  cmpyId: string;
  securityId: string;
  /** The company's own ticker. Its common shares appear as "COMMON" in the dividends table. */
  companySymbol: string;
}

interface AutocompleteRow {
  cmpyId: string;
  cmpyNm: string;
  symbol: string;
}

interface ChartRow {
  OPEN: number;
  HIGH: number;
  LOW: number;
  CLOSE: number;
  VALUE: number;
  CHART_DATE: string;
}

/** PSE Edge reports turnover in pesos, not shares, so volume is estimated as turnover / typical price. */
export function mapChart(rows: ChartRow[]): Bar[] {
  const bars: Bar[] = [];
  for (const r of rows) {
    const date = parsePseDate(r.CHART_DATE);
    if (!date || !(r.CLOSE > 0)) continue;
    const typical = (r.HIGH + r.LOW + r.CLOSE) / 3;
    bars.push({
      date,
      open: r.OPEN,
      high: r.HIGH,
      low: r.LOW,
      close: r.CLOSE,
      volume: typical > 0 ? Math.round(r.VALUE / typical) : null,
    });
  }
  return bars.sort((a, b) => a.date.localeCompare(b.date));
}

export function parseSecurityOptions(html: string): Array<{ id: string; symbol: string }> {
  const select = html.match(/<select[^>]*name="security_id"[^>]*>([\s\S]*?)<\/select>/i);
  if (!select) return [];
  return [...select[1]!.matchAll(/<option[^>]*value="(\d+)"[^>]*>\s*([^<]+?)\s*</gi)].map((m) => ({
    id: m[1]!,
    symbol: m[2]!.trim().toUpperCase(),
  }));
}

export function parseDisclosures(html: string, symbol: string): Disclosure[] {
  const out: Disclosure[] = [];
  const rowPattern =
    /openPopup\('([0-9a-f]+)'\)[^>]*>([^<]+)<\/a>\s*<\/td>\s*<td[^>]*>([^<]+)<\/td>\s*<td[^>]*>([^<]*)<\/td>\s*<td[^>]*>\s*([^<]*?)\s*<\/td>/gi;
  for (const m of html.matchAll(rowPattern)) {
    const announcedAt = parsePseDateTime(m[3]!);
    if (!announcedAt) continue;
    out.push({
      id: m[1]!,
      symbol,
      title: stripHtml(m[2]!),
      announcedAt,
      formNumber: m[4]!.trim(),
      circular: m[5]!.trim(),
      url: `${BASE}/openDiscViewer.do?edge_no=${m[1]}`,
    });
  }
  return out;
}

function parseTableRows(html: string): string[][] {
  return [...html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)].map((row) =>
    [...row[1]!.matchAll(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi)].map((c) => stripHtml(c[1]!)),
  );
}

export function parseDividends(html: string, symbol: string, isCommon: boolean): Dividend[] {
  const rows = parseTableRows(html);
  const header = rows.findIndex((r) => r[0] === "Type of Security");
  if (header < 0) return [];
  const out: Dividend[] = [];
  for (const r of rows.slice(header + 1)) {
    const security = r[0]?.toUpperCase();
    if (r.length < 6 || !(security === symbol || (isCommon && security === "COMMON"))) continue;
    const kind = r[1]!.toUpperCase();
    const type: Dividend["type"] = kind.startsWith("CASH")
      ? "CASH"
      : kind.startsWith("STOCK")
        ? "STOCK"
        : kind.startsWith("PROPERTY")
          ? "PROPERTY"
          : "OTHER";
    const amountMatch = type === "CASH" ? r[2]!.match(/(?:Php|PHP|P)\s*([\d,]*\.?\d+)/) : null;
    out.push({
      symbol,
      type,
      amount: amountMatch ? Number(amountMatch[1]!.replace(/,/g, "")) : null,
      rateText: r[2]!,
      exDate: parsePseDate(r[3]!),
      recordDate: parsePseDate(r[4]!),
      paymentDate: parsePseDate(r[5]!),
    });
  }
  return out;
}

function mmddyyyy(iso: string): string {
  const [y, m, d] = iso.split("-");
  return `${m}-${d}-${y}`;
}

export class PseEdge {
  constructor(private readonly kv: KVNamespace) {}

  /** Maps a ticker to PSE Edge's company and security ids. Cached forever because these ids never change. */
  async ids(symbol: string): Promise<PseEdgeIds> {
    const key = `pse-edge-ids:v2:${symbol}`;
    const cached = await this.kv.get<PseEdgeIds>(key, "json");
    if (cached) return cached;

    // Preferred shares and warrants (e.g. JFCPB) are listed under their parent company (JFC), which the
    // search only finds by the parent's own symbol, so retry with shorter prefixes.
    let candidates: AutocompleteRow[] = [];
    for (let len = symbol.length; len >= Math.max(2, symbol.length - 3) && candidates.length === 0; len--) {
      const term = symbol.slice(0, len);
      const res = await fetchOk("pse-edge", `${BASE}/autoComplete/searchCompanyNameSymbol.ax?term=${encodeURIComponent(term)}`);
      const companies = (await res.json()) as AutocompleteRow[];
      candidates = [
        ...companies.filter((c) => c.symbol.toUpperCase() === symbol),
        ...companies.filter((c) => c.symbol.toUpperCase() !== symbol && symbol.startsWith(c.symbol.toUpperCase())),
      ];
    }
    for (const company of candidates) {
      const page = await fetchOk("pse-edge", `${BASE}/companyPage/stockData.do?cmpy_id=${company.cmpyId}`);
      const option = parseSecurityOptions(await page.text()).find((o) => o.symbol === symbol);
      if (option) {
        const ids = { cmpyId: company.cmpyId, securityId: option.id, companySymbol: company.symbol.toUpperCase() };
        await this.kv.put(key, JSON.stringify(ids));
        return ids;
      }
    }
    throw new UpstreamError("pse-edge", `no PSE Edge listing for ${symbol}`);
  }

  async history(symbol: string, from: string, to: string): Promise<Bar[]> {
    const { cmpyId, securityId } = await this.ids(symbol);
    const res = await fetchOk("pse-edge", `${BASE}/common/DisclosureCht.ax`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ cmpy_id: cmpyId, security_id: securityId, startDate: mmddyyyy(from), endDate: mmddyyyy(to) }),
    });
    const json = (await res.json()) as { chartData?: ChartRow[] };
    return mapChart(json.chartData ?? []);
  }

  async disclosures(symbol: string): Promise<Disclosure[]> {
    const { cmpyId } = await this.ids(symbol);
    const body = new URLSearchParams({
      keyword: cmpyId,
      tmplNm: "",
      pageNo: "1",
      sortType: "date",
      dateSortType: "DESC",
      cmpySortType: "ASC",
    });
    const res = await fetchOk("pse-edge", `${BASE}/companyDisclosures/search.ax`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    return parseDisclosures(await res.text(), symbol);
  }

  async dividends(symbol: string): Promise<Dividend[]> {
    const { cmpyId, companySymbol } = await this.ids(symbol);
    const res = await fetchOk(
      "pse-edge",
      `${BASE}/companyPage/dividends_and_rights_list.ax?DividendsOrRights=Dividends&cmpy_id=${cmpyId}`,
    );
    return parseDividends(await res.text(), symbol, companySymbol === symbol);
  }

  /** Plain text of a disclosure, for AI summaries. Capped so one huge filing can't blow up token costs. */
  async disclosureText(id: string, maxChars = 12_000): Promise<string> {
    const viewer = await (await fetchOk("pse-edge", `${BASE}/openDiscViewer.do?edge_no=${encodeURIComponent(id)}`)).text();
    const src = viewer.match(/<iframe[^>]*src="([^"]+)"/i)?.[1];
    if (!src) throw new UpstreamError("pse-edge", `disclosure ${id} has no document frame`);
    const doc = await (await fetchOk("pse-edge", new URL(src, BASE).href)).text();
    return stripHtml(doc).slice(0, maxChars);
  }
}
