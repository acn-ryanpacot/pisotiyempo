import type { Source, Sourced } from "./types";

export const USER_AGENT = "Mozilla/5.0 (Linux; Android 14) PisoTiyempo/1.0";

export class UpstreamError extends Error {
  constructor(
    readonly source: Source,
    message: string,
  ) {
    super(`${source}: ${message}`);
  }
}

export async function fetchOk(source: Source, url: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(url, {
    ...init,
    headers: { "User-Agent": USER_AGENT, ...(init.headers as Record<string, string> | undefined) },
  });
  if (!res.ok) throw new UpstreamError(source, `HTTP ${res.status} for ${redact(url)}`);
  return res;
}

/** Strip API tokens before a URL lands in a log line or error message. */
export function redact(url: string): string {
  return url.replace(/(api_token=)[^&]+/i, "$1***");
}

export interface Attempt<T> {
  source: Source;
  run: () => Promise<T>;
  notes?: string[];
}

/**
 * Runs each attempt in order and returns the first usable result.
 * This is the fallback chain: EODHD first, then the unofficial sources.
 */
export async function firstUsable<T>(
  attempts: Attempt<T>[],
  usable: (value: T) => boolean,
  log: (msg: string) => void = console.warn,
): Promise<Sourced<T>> {
  const failures: string[] = [];
  for (const attempt of attempts) {
    try {
      const data = await attempt.run();
      if (usable(data)) {
        return {
          source: attempt.source,
          fetchedAt: new Date().toISOString(),
          ...(attempt.notes ? { notes: attempt.notes } : {}),
          data,
        };
      }
      failures.push(`${attempt.source}: empty result`);
    } catch (err) {
      failures.push(err instanceof Error ? err.message : String(err));
    }
  }
  log(`all sources failed: ${failures.join(" | ")}`);
  throw new AllSourcesFailedError(failures);
}

export class AllSourcesFailedError extends Error {
  constructor(readonly failures: string[]) {
    super(`All data sources failed: ${failures.join(" | ")}`);
  }
}

const MONTHS: Record<string, string> = {
  Jan: "01", Feb: "02", Mar: "03", Apr: "04", May: "05", Jun: "06",
  Jul: "07", Aug: "08", Sep: "09", Oct: "10", Nov: "11", Dec: "12",
};

/** Parses PSE Edge dates such as "Oct 21, 2026", "Jul 2, 2026" or "Jan 02, 2026 00:00:00" to YYYY-MM-DD. */
export function parsePseDate(text: string): string | null {
  const m = text.trim().match(/^([A-Z][a-z]{2})\s+(\d{1,2}),\s*(\d{4})/);
  if (!m) return null;
  const month = MONTHS[m[1]!];
  if (!month) return null;
  return `${m[3]}-${month}-${m[2]!.padStart(2, "0")}`;
}

/** Parses "Oct 21, 2026 12:15 PM" to an ISO timestamp in PHT. */
export function parsePseDateTime(text: string): string | null {
  const date = parsePseDate(text);
  const t = text.match(/(\d{1,2}):(\d{2})\s*([AP]M)/i);
  if (!date) return null;
  if (!t) return `${date}T00:00:00+08:00`;
  let hour = Number(t[1]) % 12;
  if (t[3]!.toUpperCase() === "PM") hour += 12;
  return `${date}T${String(hour).padStart(2, "0")}:${t[2]}:00+08:00`;
}

/** Today's date in Philippine time (UTC+8, no DST). */
export function phtToday(now: Date = new Date()): string {
  return new Date(now.getTime() + 8 * 3600_000).toISOString().slice(0, 10);
}

export function addDays(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function stripHtml(html: string): string {
  return html
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, " ")
    .trim();
}

export const SYMBOL_PATTERN = /^[A-Z0-9]{1,8}$/;
