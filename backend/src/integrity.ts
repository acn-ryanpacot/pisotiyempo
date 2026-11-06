// Play Integrity: the app proves it is a genuine Play install once, then uses a short-lived session token.
// Only POST /v1/auth/session calls Google, so the daily integrity quota is spent once per install per day.

export type IntegrityMode = "off" | "monitor" | "enforce";

export function integrityMode(value: string | undefined): IntegrityMode {
  return value === "monitor" || value === "enforce" ? value : "off";
}

/** The decoded verdict, as returned in `tokenPayloadExternal` by the decodeIntegrityToken API. */
export interface IntegrityPayload {
  requestDetails?: { requestPackageName?: string; timestampMillis?: string; requestHash?: string };
  appIntegrity?: { appRecognitionVerdict?: string; packageName?: string };
  deviceIntegrity?: { deviceRecognitionVerdict?: string[] };
}

export interface Verdict {
  ok: boolean;
  reasons: string[];
}

const MAX_TOKEN_AGE_MS = 5 * 60_000;

/** Pure check of a decoded verdict, kept separate from the network calls so it can be unit-tested. */
export function evaluateVerdict(p: IntegrityPayload, packageName: string, requestHash: string, now = Date.now()): Verdict {
  const reasons: string[] = [];
  if (p.requestDetails?.requestPackageName !== packageName) reasons.push("wrong package");
  if (p.requestDetails?.requestHash !== requestHash) reasons.push("request hash mismatch");
  const ts = Number(p.requestDetails?.timestampMillis ?? 0);
  if (!(ts > 0) || Math.abs(now - ts) > MAX_TOKEN_AGE_MS) reasons.push("token too old");
  if (p.appIntegrity?.appRecognitionVerdict !== "PLAY_RECOGNIZED") reasons.push(`app not recognized (${p.appIntegrity?.appRecognitionVerdict ?? "none"})`);
  if (!(p.deviceIntegrity?.deviceRecognitionVerdict ?? []).includes("MEETS_DEVICE_INTEGRITY")) reasons.push("device integrity not met");
  return { ok: reasons.length === 0, reasons };
}

/** The value the app must put in the integrity request's requestHash for a given install id. */
export async function sessionRequestHash(installId: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`piso-session:${installId}`)));
}

// ---- Session tokens: base64url(json payload).base64url(HMAC-SHA256) ----------------------------------

export interface Session {
  installId: string;
  exp: number;
  /** Epoch ms until which this install has an active premium subscription; absent for free users. */
  premiumUntil?: number;
}

export const SESSION_TTL_MS = 24 * 3600_000;

export async function issueSession(
  installId: string,
  secret: string,
  now = Date.now(),
  premiumUntil?: number,
): Promise<{ token: string; expiresAt: number; premiumUntil: number | null }> {
  const payload: Session = { installId, exp: now + SESSION_TTL_MS, ...(premiumUntil ? { premiumUntil } : {}) };
  const body = b64url(new TextEncoder().encode(JSON.stringify(payload)));
  const sig = b64url(await hmac(secret, body));
  return { token: `${body}.${sig}`, expiresAt: payload.exp, premiumUntil: premiumUntil ?? null };
}

export async function verifySession(token: string, secret: string, now = Date.now()): Promise<Session | null> {
  const [body, sig] = token.split(".");
  if (!body || !sig) return null;
  const key = await hmacKey(secret);
  const valid = await crypto.subtle.verify("HMAC", key, fromB64url(sig), new TextEncoder().encode(body));
  if (!valid) return null;
  try {
    const session = JSON.parse(new TextDecoder().decode(fromB64url(body))) as Session;
    return typeof session.exp === "number" && session.exp > now ? session : null;
  } catch {
    return null;
  }
}

// ---- Google API calls --------------------------------------------------------------------------------

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

const cachedAccessTokens = new Map<string, { token: string; expiresAt: number }>();

export const SCOPE_PLAY_INTEGRITY = "https://www.googleapis.com/auth/playintegrity";
export const SCOPE_ANDROID_PUBLISHER = "https://www.googleapis.com/auth/androidpublisher";

/** OAuth access token for a Google API, from a service-account key (JWT bearer grant). */
export async function googleAccessToken(serviceAccountJson: string, scope: string): Promise<string> {
  const cachedToken = cachedAccessTokens.get(scope);
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) return cachedToken.token;
  const sa = JSON.parse(serviceAccountJson) as ServiceAccount;
  const now = Math.floor(Date.now() / 1000);
  const enc = (o: unknown) => b64url(new TextEncoder().encode(JSON.stringify(o)));
  const unsigned = `${enc({ alg: "RS256", typ: "JWT" })}.${enc({
    iss: sa.client_email,
    scope,
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })}`;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", fromB64(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = b64url(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned))));
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${sig}` }),
  });
  if (!res.ok) throw new Error(`Google token exchange failed: HTTP ${res.status}`);
  const json = (await res.json()) as { access_token: string; expires_in: number };
  cachedAccessTokens.set(scope, { token: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 });
  return json.access_token;
}

export async function decodeIntegrityToken(integrityToken: string, packageName: string, serviceAccountJson: string): Promise<IntegrityPayload> {
  const access = await googleAccessToken(serviceAccountJson, SCOPE_PLAY_INTEGRITY);
  const res = await fetch(`https://playintegrity.googleapis.com/v1/${encodeURIComponent(packageName)}:decodeIntegrityToken`, {
    method: "POST",
    headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
    body: JSON.stringify({ integrity_token: integrityToken }),
  });
  if (!res.ok) throw new Error(`decodeIntegrityToken failed: HTTP ${res.status}`);
  return ((await res.json()) as { tokenPayloadExternal: IntegrityPayload }).tokenPayloadExternal;
}

// ---- Encoding helpers ---------------------------------------------------------------------------------

async function hmacKey(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function hmac(secret: string, data: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), new TextEncoder().encode(data)));
}

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64url(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromB64(b64: string): Uint8Array {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  return fromB64(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
}
