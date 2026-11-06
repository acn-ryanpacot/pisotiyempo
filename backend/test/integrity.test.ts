import { describe, expect, it } from "vitest";
import { evaluateVerdict, integrityMode, issueSession, sessionRequestHash, verifySession, type IntegrityPayload } from "../src/integrity";

describe("Play Integrity verdicts", () => {
  const now = 1_800_000_000_000;
  const good = async (): Promise<IntegrityPayload> => ({
    requestDetails: { requestPackageName: "com.pisotiyempo", timestampMillis: String(now - 1000), requestHash: await sessionRequestHash("abcdef0123456789") },
    appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: "com.pisotiyempo" },
    deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY", "MEETS_BASIC_INTEGRITY"] },
  });

  it("accepts a genuine Play install on a certified device", async () => {
    const hash = await sessionRequestHash("abcdef0123456789");
    expect(evaluateVerdict(await good(), "com.pisotiyempo", hash, now)).toEqual({ ok: true, reasons: [] });
  });

  it("rejects sideloaded apps, rooted devices, replayed and old tokens", async () => {
    const hash = await sessionRequestHash("abcdef0123456789");
    const p = await good();
    expect(evaluateVerdict({ ...p, appIntegrity: { appRecognitionVerdict: "UNRECOGNIZED_VERSION" } }, "com.pisotiyempo", hash, now).ok).toBe(false);
    expect(evaluateVerdict({ ...p, deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_BASIC_INTEGRITY"] } }, "com.pisotiyempo", hash, now).ok).toBe(false);
    expect(evaluateVerdict(p, "com.pisotiyempo", await sessionRequestHash("another-install-id"), now).reasons).toContain("request hash mismatch");
    expect(evaluateVerdict(p, "com.pisotiyempo", hash, now + 10 * 60_000).reasons).toContain("token too old");
  });

  it("defaults to off for unknown modes", () => {
    expect(integrityMode(undefined)).toBe("off");
    expect(integrityMode("ENFORCE")).toBe("off");
    expect(integrityMode("enforce")).toBe("enforce");
  });
});

describe("session tokens", () => {
  it("round-trips and expires", async () => {
    const { token, expiresAt } = await issueSession("abcdef0123456789", "secret", 1000);
    expect(await verifySession(token, "secret", 2000)).toEqual({ installId: "abcdef0123456789", exp: expiresAt });
    expect(await verifySession(token, "secret", expiresAt + 1)).toBeNull();
  });

  it("rejects tampered tokens and the wrong secret", async () => {
    const { token } = await issueSession("abcdef0123456789", "secret", 1000);
    const [body, sig] = token.split(".");
    const forged = Buffer.from(JSON.stringify({ installId: "attacker", exp: 9e15 })).toString("base64url");
    expect(await verifySession(`${forged}.${sig}`, "secret", 2000)).toBeNull();
    expect(await verifySession(`${body}.${sig}`, "other-secret", 2000)).toBeNull();
    expect(await verifySession("garbage", "secret", 2000)).toBeNull();
  });
});

describe("premium subscriptions", async () => {
  const { premiumUntil } = await import("../src/billing");
  const now = Date.parse("2026-11-06T00:00:00Z");

  it("grants premium for active and grace-period subscriptions until expiry", () => {
    const line = { productId: "piso_premium", expiryTime: "2026-12-06T00:00:00Z" };
    expect(premiumUntil({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [line] }, now)).toBe(Date.parse(line.expiryTime));
    expect(premiumUntil({ subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", lineItems: [line] }, now)).toBe(Date.parse(line.expiryTime));
  });

  it("grants nothing for expired, canceled-and-lapsed, or other products", () => {
    expect(premiumUntil({ subscriptionState: "SUBSCRIPTION_STATE_EXPIRED", lineItems: [{ productId: "piso_premium", expiryTime: "2026-12-06T00:00:00Z" }] }, now)).toBeNull();
    expect(premiumUntil({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "piso_premium", expiryTime: "2026-11-01T00:00:00Z" }] }, now)).toBeNull();
    expect(premiumUntil({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [{ productId: "other", expiryTime: "2026-12-06T00:00:00Z" }] }, now)).toBeNull();
  });

  it("carries premium in the session token", async () => {
    const until = now + 86_400_000 * 30;
    const { token } = await issueSession("abcdef0123456789", "secret", now, until);
    expect((await verifySession(token, "secret", now + 1000))?.premiumUntil).toBe(until);
  });
});
