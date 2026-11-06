import Anthropic from "@anthropic-ai/sdk";
import { AiUnavailableError, analyzeStock, parseLang, reviewJournal, summarizeDisclosure, writeMarketBrief, type Lang } from "./ai/claude";
import { parseJournalInput } from "./ai/journal";
import { parseFeatures, ValidationError, type MarketBrief } from "./ai/schemas";
import { CLOSURES_KEY, type Closures } from "./calendar";
import { fetchSubscription, premiumUntil } from "./billing";
import { decodeIntegrityToken, evaluateVerdict, integrityMode, issueSession, sessionRequestHash, verifySession, type Session } from "./integrity";
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

  // Adds (or with "remove": true, deletes) an ad-hoc market closure, e.g. a typhoon suspension or an Eid date.
  if (path === "/v1/admin/closure" && request.method === "POST") {
    if (!env.ADMIN_KEY || request.headers.get("X-Admin-Key") !== env.ADMIN_KEY) return error(401, "unauthorized", "Bad admin key");
    const body = (await request.json()) as { date?: unknown; name?: unknown; remove?: unknown };
    if (typeof body.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(body.date)) return error(400, "invalid", "date (YYYY-MM-DD) is required");
    const closures = (await env.CACHE.get<Closures>(CLOSURES_KEY, "json")) ?? {};
    if (body.remove === true) delete closures[body.date];
    else closures[body.date] = typeof body.name === "string" && body.name.trim() ? body.name.trim().slice(0, 80) : "Market closed";
    await env.CACHE.put(CLOSURES_KEY, JSON.stringify(closures));
    return json({ ok: true, closures });
  }

  if (env.APP_KEY && request.headers.get("X-App-Key") !== env.APP_KEY) return error(401, "unauthorized", "Missing or bad app key");

  const mode = integrityMode(env.INTEGRITY_MODE);
  if (path === "/v1/auth/session" && request.method === "POST") return createSession(request, env, mode);
  const bearer = request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  const session: Session | null = env.SESSION_SECRET && bearer ? await verifySession(bearer, env.SESSION_SECRET) : null;
  // /v1/status stays open so the app's Settings screen can diagnose a broken setup.
  if (mode !== "off" && path !== "/v1/status" && !session) {
    if (mode === "enforce") return error(401, "integrity_required", "Request a session with Play Integrity first.");
    console.warn(`integrity monitor: ${path} called without a valid session`);
  }
  const premiumAi = env.PREMIUM_AI === "true";
  const isPremium = (session?.premiumUntil ?? 0) > Date.now();

  if (request.method === "GET") {
    if (path === "/v1/status") {
      const calendar = await market.calendar();
      const today = phtToday();
      return json({
        eodhdConfigured: !!env.EODHD_API_KEY,
        aiEnabled: aiEnabled(env),
        today,
        marketClosedToday: calendar.closureName(today),
        premiumRequiredForAi: premiumAi,
        premium: isPremium,
      });
    }
    if (path === "/v1/calendar") {
      const calendar = await market.calendar();
      return json({ holidays: calendar.upcoming(isoDateParam(url, "from", `${phtToday().slice(0, 4)}-01-01`)) }, 200, 3600);
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
      if (!stored) return error(404, "not_available", "No brief has been generated yet.");
      const lang = parseLang(url.searchParams.get("lang"));
      if (lang === "en") return json(stored, 200, 900);
      // Other languages are written on first request for the day and kept for everyone.
      const key = `brief:latest:${lang}`;
      const translated = await env.CACHE.get<StoredBrief>(key, "json");
      if (translated?.date === stored.date) return json(translated, 200, 900);
      if (!aiEnabled(env)) return json(stored, 200, 900);
      const fresh = await briefFor(market, env.ANTHROPIC_API_KEY, stored.date, lang);
      await env.CACHE.put(key, JSON.stringify(fresh));
      return json(fresh, 200, 900);
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
    if (premiumAi && !isPremium) return error(402, "premium_required", "AI analysis is part of Premium.");
    const body = (await request.json()) as Record<string, unknown>;
    const features = parseFeatures(body);
    const lang = parseLang(body.lang);
    // The analysis is shared by every user who opens this stock today, so cost grows with stocks, not users.
    const key = `analysis:${features.symbol}:${features.asOf}:${lang}`;
    const hit = await cacheGet(key);
    if (hit) return json(hit);

    const snapshot = await market.latest();
    const quote = snapshot.data.quotes.find((q) => q.symbol === features.symbol);
    if (!quote) return error(404, "unknown_symbol", `${features.symbol} is not listed`);
    if (features.asOf !== quote.date) return error(409, "stale", `Expected data as of ${quote.date}; sync first.`);
    if (Math.abs(features.close - quote.close) / quote.close > 0.02) return error(409, "mismatch", "Close price does not match market data.");

    const analysis = await analyzeStock(env.ANTHROPIC_API_KEY, features, lang);
    const result = { symbol: features.symbol, asOf: features.asOf, model: "claude-haiku-5-5", analysis };
    ctx.waitUntil(cachePut(key, result, 24 * 3600));
    return json(result);
  }

  if (request.method === "POST" && path === "/v1/ai/disclosure") {
    if (!aiEnabled(env)) return error(503, "ai_unavailable", "AI analysis is switched off.");
    if (premiumAi && !isPremium) return error(402, "premium_required", "AI summaries are part of Premium.");
    const body = (await request.json()) as { id?: unknown; symbol?: unknown; lang?: unknown };
    const lang = parseLang(body.lang);
    const id = typeof body.id === "string" && /^[0-9a-f]{16,40}$/.test(body.id) ? body.id : null;
    const symbol = typeof body.symbol === "string" ? body.symbol.toUpperCase() : "";
    if (!id || !SYMBOL_PATTERN.test(symbol)) return error(400, "invalid", "id and symbol are required");
    const key = `disclosure:${id}:${lang}`;
    const hit = await cacheGet(key);
    if (hit) return json(hit);

    // Only summarize disclosures that really belong to this symbol, using the title PSE Edge reports.
    const disclosure = (await market.disclosures(symbol)).data.find((d) => d.id === id);
    if (!disclosure) return error(404, "not_found", "Disclosure not found for this symbol");
    const text = await market.disclosureText(id);
    const summary = await summarizeDisclosure(env.ANTHROPIC_API_KEY, disclosure.title, text, lang);
    const result = { id, model: "claude-haiku-5-5", summary };
    ctx.waitUntil(cachePut(key, result, 30 * 24 * 3600));
    return json(result);
  }

  if (request.method === "POST" && path === "/v1/ai/journal-review") {
    if (!aiEnabled(env)) return error(503, "ai_unavailable", "AI analysis is switched off.");
    if (premiumAi && !isPremium) return error(402, "premium_required", "AI coaching is part of Premium.");
    const body = (await request.json()) as Record<string, unknown>;
    const input = parseJournalInput(body);
    // One review per install (or per IP without a session) per day: each one is a personal Opus call.
    const who = session?.installId ?? request.headers.get("CF-Connecting-IP") ?? "anonymous";
    const limitKey = `journal-limit:${who}:${phtToday()}`;
    if (await cacheGet(limitKey)) return error(429, "rate_limited", "You can get one AI review a day. Try again tomorrow.");
    const review = await reviewJournal(env.ANTHROPIC_API_KEY, input, parseLang(body.lang));
    ctx.waitUntil(cachePut(limitKey, { at: Date.now() }, 24 * 3600));
    return json({ model: "claude-opus-5-5", review });
  }

  return error(404, "not_found", "Unknown endpoint");
}

async function briefFor(market: Market, apiKey: string, date: string, lang: Lang): Promise<StoredBrief> {
  const snapshot = await market.latest();
  const psei = await market.psei(addDays(date, -10)).catch(() => null);
  const stats = computeMarketStats(snapshot.data.date, snapshot.data.quotes, psei?.data ?? []);
  const brief = await writeMarketBrief(apiKey, stats, lang);
  return { date: snapshot.data.date, model: "claude-opus-5-5", generatedAt: new Date().toISOString(), brief };
}

/**
 * Exchanges a Play Integrity token for a 24-hour session token. In "monitor" mode a failed verdict is
 * logged but still gets a session, so you can watch real-world verdicts before enforcing.
 */
async function createSession(request: Request, env: Env, mode: ReturnType<typeof integrityMode>): Promise<Response> {
  if (!env.SESSION_SECRET) return error(503, "integrity_unconfigured", "SESSION_SECRET is not set.");
  const body = (await request.json()) as { installId?: unknown; integrityToken?: unknown; purchaseToken?: unknown };
  const installId = typeof body.installId === "string" && /^[0-9a-f-]{16,64}$/.test(body.installId) ? body.installId : null;
  if (!installId) return error(400, "invalid", "installId is required");

  if (mode !== "off") {
    if (typeof body.integrityToken !== "string" || !body.integrityToken) return error(400, "invalid", "integrityToken is required");
    if (!env.GOOGLE_SERVICE_ACCOUNT_JSON) return error(503, "integrity_unconfigured", "GOOGLE_SERVICE_ACCOUNT_JSON is not set.");
    const packageName = env.PLAY_PACKAGE_NAME ?? "com.pisotiyempo";
    let reasons: string[];
    try {
      const payload = await decodeIntegrityToken(body.integrityToken, packageName, env.GOOGLE_SERVICE_ACCOUNT_JSON);
      reasons = evaluateVerdict(payload, packageName, await sessionRequestHash(installId)).reasons;
    } catch (err) {
      reasons = [err instanceof Error ? err.message : String(err)];
    }
    if (reasons.length > 0) {
      if (mode === "enforce") return error(403, "integrity_failed", reasons.join("; "));
      console.warn(`integrity monitor: verdict failed for ${installId}: ${reasons.join("; ")}`);
    }
  }

  // Premium: confirm the Play subscription server-side. A failed lookup just means a free session.
  let premium: number | undefined;
  if (typeof body.purchaseToken === "string" && body.purchaseToken && env.GOOGLE_SERVICE_ACCOUNT_JSON) {
    try {
      const sub = await fetchSubscription(env.PLAY_PACKAGE_NAME ?? "com.pisotiyempo", body.purchaseToken, env.GOOGLE_SERVICE_ACCOUNT_JSON);
      premium = premiumUntil(sub) ?? undefined;
    } catch (err) {
      console.warn(`premium check failed for ${installId}: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
  return json(await issueSession(installId, env.SESSION_SECRET, Date.now(), premium));
}

/** After the close: refresh the shared snapshot, then write the day's Opus brief once. */
async function dailyJob(env: Env): Promise<void> {
  const market = new Market(env);
  // Nothing trades on weekends and holidays, so there is nothing new to fetch or write about.
  if (!(await market.calendar()).isTradingDay(phtToday())) return;
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
