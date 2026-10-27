import Anthropic from "@anthropic-ai/sdk";
import { AiUnavailableError, analyzeStock, summarizeDisclosure, writeMarketBrief } from "./ai/claude";
import { parseFeatures, ValidationError, type MarketBrief } from "./ai/schemas";
import { Market } from "./market";
import { computeMarketStats } from "./stats";
import type { Env, ForeignFlowDay } from "./types";
import { AllSourcesFailedError, addDays, phtToday, SYMBOL_PATTERN } from "./util";

interface StoredBrief {
  date: string;
  model: string;
  generatedAt: string;
  brief: MarketBrief;
}

const json = (body: unknown, status = 200, maxAge = 0) =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": maxAge > 0 ? `public, max-age=${maxAge}` : "no-store",
    },
  });

const error = (status: number, code: string, message: string) => json({ error: code, message }, status);

function aiEnabled(env: Env): env is Env & { ANTHROPIC_API_KEY: string } {
  return env.AI_ENABLED !== "false" && !!env.ANTHROPIC_API_KEY;
}

function isoDateParam(url: URL, name: string, fallback: string): string {
  const v = url.searchParams.get(name);
  return v && /^\d{4}-\d{2}-\d{2}$/.test(v) ? v : fallback;
}

/** Cache API helpers for AI results: free, no daily write cap, and scoped per data centre. */
async function cacheGet<T>(key: string): Promise<T | null> {
  const hit = await (caches as unknown as { default: Cache }).default.match(`https://ai.piso-tiyempo.internal/${encodeURIComponent(key)}`);
  return hit ? ((await hit.json()) as T) : null;
}

async function cachePut(key: string, value: unknown, ttlSeconds: number): Promise<void> {
  await (caches as unknown as { default: Cache }).default.put(
    `https://ai.piso-tiyempo.internal/${encodeURIComponent(key)}`,
    new Response(JSON.stringify(value), { headers: { "Cache-Control": `max-age=${ttlSeconds}` } }),
  );
}

async function handle(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "");
  const market = new Market(env);

  if (path === "/v1/admin/foreign-flow" && request.method === "POST") {
    if (!env.ADMIN_KEY || request.headers.get("X-Admin-Key") !== env.ADMIN_KEY) return error(401, "unauthorized", "Bad admin key");
    const day = (await request.json()) as ForeignFlowDay;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day.date) || typeof day.netValue !== "number") return error(400, "invalid", "date and netValue are required");
    await market.putForeignFlow(day);
    return json({ ok: true });
  }

  if (env.APP_KEY && request.headers.get("X-App-Key") !== env.APP_KEY) return error(401, "unauthorized", "Missing or bad app key");

  if (request.method === "GET") {
    if (path === "/v1/status") {
      return json({
        eodhdConfigured: !!env.EODHD_API_KEY,
        aiEnabled: aiEnabled(env),
        today: phtToday(),
      });
    }
    if (path === "/v1/listings") return json(await market.listings(), 200, 3600);
    if (path === "/v1/eod/latest") return json(await market.latest(), 200, 300);
    if (path === "/v1/psei") return json(await market.psei(isoDateParam(url, "from", addDays(phtToday(), -730))), 200, 900);
    if (path === "/v1/foreign-flow") {
      const flow = await market.foreignFlow();
      return flow ? json(flow, 200, 900) : error(404, "not_available", "No licensed foreign-flow feed is configured.");
    }
    if (path === "/v1/ai/brief") {
      const stored = await env.CACHE.get<StoredBrief>("brief:latest", "json");
      return stored ? json(stored, 200, 900) : error(404, "not_available", "No brief has been generated yet.");
    }

    const m = path.match(/^\/v1\/(history|dividends|disclosures)\/([A-Za-z0-9]+)$/);
    if (m) {
      const symbol = m[2]!.toUpperCase();
      if (!SYMBOL_PATTERN.test(symbol)) return error(400, "invalid", "Bad symbol");
      if (m[1] === "history") return json(await market.history(symbol, isoDateParam(url, "from", addDays(phtToday(), -730))), 200, 900);
      if (m[1] === "dividends") return json(await market.dividends(symbol), 200, 3600);
      return json(await market.disclosures(symbol), 200, 900);
    }
  }

  if (request.method === "POST" && path === "/v1/ai/analyze") {
    if (!aiEnabled(env)) return error(503, "ai_unavailable", "AI analysis is switched off.");
    const features = parseFeatures(await request.json());
    // The analysis is shared by every user who opens this stock today, so cost grows with stocks, not users.
    const key = `analysis:${features.symbol}:${features.asOf}`;
    const hit = await cacheGet(key);
    if (hit) return json(hit);

    const snapshot = await market.latest();
    const quote = snapshot.data.quotes.find((q) => q.symbol === features.symbol);
    if (!quote) return error(404, "unknown_symbol", `${features.symbol} is not listed`);
    if (features.asOf !== quote.date) return error(409, "stale", `Expected data as of ${quote.date}; sync first.`);
    if (Math.abs(features.close - quote.close) / quote.close > 0.02) return error(409, "mismatch", "Close price does not match market data.");

    const analysis = await analyzeStock(env.ANTHROPIC_API_KEY, features);
    const result = { symbol: features.symbol, asOf: features.asOf, model: "claude-haiku-5-5", analysis };
    ctx.waitUntil(cachePut(key, result, 24 * 3600));
    return json(result);
  }

  if (request.method === "POST" && path === "/v1/ai/disclosure") {
    if (!aiEnabled(env)) return error(503, "ai_unavailable", "AI analysis is switched off.");
    const body = (await request.json()) as { id?: unknown; symbol?: unknown };
    const id = typeof body.id === "string" && /^[0-9a-f]{16,40}$/.test(body.id) ? body.id : null;
    const symbol = typeof body.symbol === "string" ? body.symbol.toUpperCase() : "";
    if (!id || !SYMBOL_PATTERN.test(symbol)) return error(400, "invalid", "id and symbol are required");
    const key = `disclosure:${id}`;
    const hit = await cacheGet(key);
    if (hit) return json(hit);

    // Only summarize disclosures that really belong to this symbol, using the title PSE Edge reports.
    const disclosure = (await market.disclosures(symbol)).data.find((d) => d.id === id);
    if (!disclosure) return error(404, "not_found", "Disclosure not found for this symbol");
    const text = await market.disclosureText(id);
    const summary = await summarizeDisclosure(env.ANTHROPIC_API_KEY, disclosure.title, text);
    const result = { id, model: "claude-haiku-5-5", summary };
    ctx.waitUntil(cachePut(key, result, 30 * 24 * 3600));
    return json(result);
  }

  return error(404, "not_found", "Unknown endpoint");
}

/** After the close: refresh the shared snapshot, then write the day's Opus brief once. */
async function dailyJob(env: Env): Promise<void> {
  const market = new Market(env);
  const snapshot = await market.latest(true);
  if (!aiEnabled(env)) return;
  const existing = await env.CACHE.get<StoredBrief>("brief:latest", "json");
  if (existing?.date === snapshot.data.date) return;

  const psei = await market.psei(addDays(snapshot.data.date, -10)).catch(() => null);
  const stats = computeMarketStats(snapshot.data.date, snapshot.data.quotes, psei?.data ?? []);
  const brief = await writeMarketBrief(env.ANTHROPIC_API_KEY, stats);
  const stored: StoredBrief = { date: snapshot.data.date, model: "claude-opus-5-5", generatedAt: new Date().toISOString(), brief };
  await env.CACHE.put("brief:latest", JSON.stringify(stored));
}

export default {
  async fetch(request, env, ctx): Promise<Response> {
    try {
      return await handle(request, env, ctx);
    } catch (err) {
      if (err instanceof ValidationError) return error(400, "invalid", err.message);
      if (err instanceof AllSourcesFailedError) return error(502, "upstream_unavailable", err.message);
      if (err instanceof AiUnavailableError) return error(503, "ai_unavailable", err.message);
      if (err instanceof Anthropic.RateLimitError) return error(503, "ai_unavailable", "AI is rate limited; try later.");
      if (err instanceof Anthropic.APIError) return error(503, "ai_unavailable", `AI error ${err.status}`);
      if (err instanceof SyntaxError) return error(400, "invalid", "Body is not valid JSON");
      console.error(err);
      return error(500, "internal", "Unexpected error");
    }
  },

  async scheduled(_controller, env, ctx): Promise<void> {
    ctx.waitUntil(dailyJob(env));
  },
} satisfies ExportedHandler<Env>;
