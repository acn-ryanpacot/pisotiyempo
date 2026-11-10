// Encrypted sync relay. Devices in a sync group push AES-GCM blobs keyed by record uid; the server stores and
// returns them with an increasing per-group sequence number. It never has the key, so it can't read anything.
// Membership is proven with an auth token derived from the key; only its SHA-256 hash is stored.

export interface SyncGroup {
  groupId: string;
  tokenHash: string;
  /** Registered or used by an install with a valid (Play Integrity) session, so desktops may join it. */
  verified: boolean;
  premiumUntil: number | null;
  recordCount: number;
  seq: number;
}

export interface StoredBlob {
  uid: string;
  blob: string;
  seq: number;
}

/** Storage behind the relay; D1 in production, in-memory in tests. */
export interface SyncDb {
  getGroup(groupId: string): Promise<SyncGroup | null>;
  createGroup(groupId: string, tokenHash: string, verified: boolean, now: number): Promise<void>;
  /** Stores the blobs, assigning consecutive sequence numbers. Returns the group's new highest sequence. */
  upsert(groupId: string, blobs: Array<{ uid: string; blob: string }>, now: number): Promise<number>;
  pull(groupId: string, since: number, limit: number): Promise<StoredBlob[]>;
  markVerified(groupId: string, premiumUntil: number | null): Promise<void>;
  deleteGroup(groupId: string): Promise<void>;
}

export const LIMITS = { recordsPerPush: 500, blobChars: 16_384, recordsPerGroup: 50_000, pullPage: 500 };

const GROUP_RE = /^[0-9a-f]{32}$/;
const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
const UID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export class SyncError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

export async function sha256Hex(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export interface SyncAuth {
  groupId: string;
  token: string;
}

export function readAuth(headers: Headers): SyncAuth {
  const groupId = headers.get("X-Sync-Group") ?? "";
  const token = headers.get("X-Sync-Token") ?? "";
  if (!GROUP_RE.test(groupId) || !TOKEN_RE.test(token)) throw new SyncError(400, "invalid", "Bad sync credentials");
  return { groupId, token };
}

/** Checks the token against an existing group. Returns null when the group doesn't exist yet. */
export async function authenticate(db: SyncDb, auth: SyncAuth): Promise<SyncGroup | null> {
  const group = await db.getGroup(auth.groupId);
  if (!group) return null;
  if (!timingSafeEqual(group.tokenHash, await sha256Hex(auth.token))) throw new SyncError(403, "forbidden", "Wrong sync token");
  return group;
}

export interface Caller {
  /** True when the request carries a valid session token (a verified phone, or any install while integrity is off). */
  hasSession: boolean;
  premiumUntil: number | null;
  /** INTEGRITY_MODE is "enforce": only verified installs may create groups. */
  enforce: boolean;
}

export async function push(db: SyncDb, auth: SyncAuth, body: unknown, caller: Caller, now = Date.now()): Promise<{ stored: number; cursor: number }> {
  const records = (body as { records?: unknown })?.records;
  if (!Array.isArray(records) || records.length > LIMITS.recordsPerPush) throw new SyncError(400, "invalid", `records must be an array of at most ${LIMITS.recordsPerPush}`);
  const blobs = records.map((r) => {
    const o = (r ?? {}) as Record<string, unknown>;
    if (typeof o.uid !== "string" || !UID_RE.test(o.uid) || typeof o.blob !== "string" || o.blob.length > LIMITS.blobChars) {
      throw new SyncError(400, "invalid", "Each record needs a uid and a blob under 16 KB");
    }
    return { uid: o.uid, blob: o.blob };
  });

  let group = await authenticate(db, auth);
  if (!group) {
    // A desktop alone can't create a group when enforcing: it waits until the user's phone links.
    if (caller.enforce && !caller.hasSession) throw new SyncError(403, "group_not_registered", "Link this device from the phone app first.");
    await db.createGroup(auth.groupId, await sha256Hex(auth.token), caller.hasSession, now);
    group = (await db.getGroup(auth.groupId))!;
  }
  if (group.recordCount + blobs.length > LIMITS.recordsPerGroup) throw new SyncError(413, "quota", "Sync storage limit reached");
  if (caller.hasSession && (!group.verified || caller.premiumUntil !== group.premiumUntil)) {
    await db.markVerified(auth.groupId, caller.premiumUntil);
  }
  const cursor = blobs.length ? await db.upsert(auth.groupId, blobs, now) : group.seq;
  return { stored: blobs.length, cursor };
}

export async function pull(db: SyncDb, auth: SyncAuth, since: number, caller: Caller): Promise<{ records: StoredBlob[]; cursor: number; more: boolean }> {
  const group = await authenticate(db, auth);
  if (!group) return { records: [], cursor: 0, more: false };
  if (caller.hasSession && (!group.verified || caller.premiumUntil !== group.premiumUntil)) await db.markVerified(auth.groupId, caller.premiumUntil);
  const records = await db.pull(auth.groupId, since, LIMITS.pullPage + 1);
  const more = records.length > LIMITS.pullPage;
  const page = records.slice(0, LIMITS.pullPage);
  return { records: page, cursor: page.at(-1)?.seq ?? Math.max(since, 0), more };
}

// ---- D1 ---------------------------------------------------------------------------------------------

export class D1SyncDb implements SyncDb {
  constructor(private readonly d1: D1Database) {}

  async getGroup(groupId: string): Promise<SyncGroup | null> {
    const row = await this.d1
      .prepare("SELECT group_id, token_hash, verified, premium_until, record_count, seq FROM sync_groups WHERE group_id = ?")
      .bind(groupId)
      .first<{ group_id: string; token_hash: string; verified: number; premium_until: number | null; record_count: number; seq: number }>();
    return row
      ? { groupId: row.group_id, tokenHash: row.token_hash, verified: row.verified === 1, premiumUntil: row.premium_until, recordCount: row.record_count, seq: row.seq }
      : null;
  }

  async createGroup(groupId: string, tokenHash: string, verified: boolean, now: number): Promise<void> {
    await this.d1
      .prepare("INSERT OR IGNORE INTO sync_groups (group_id, token_hash, verified, record_count, seq, created_at, updated_at) VALUES (?, ?, ?, 0, 0, ?, ?)")
      .bind(groupId, tokenHash, verified ? 1 : 0, now, now)
      .run();
  }

  async upsert(groupId: string, blobs: Array<{ uid: string; blob: string }>, now: number): Promise<number> {
    // One batch = one transaction: bump the group's sequence by n, then give the i-th record seq - n + 1 + i.
    // Doing the reservation inside the same transaction means a concurrent pull can never see a higher
    // sequence before the lower ones are written, so no device skips records.
    const n = blobs.length;
    const stmts: D1PreparedStatement[] = [
      this.d1.prepare("UPDATE sync_groups SET seq = seq + ?, updated_at = ? WHERE group_id = ?").bind(n, now, groupId),
      ...blobs.map((b, i) =>
        this.d1
          .prepare(
            "INSERT INTO sync_records (group_id, uid, seq, blob) VALUES (?, ?, (SELECT seq FROM sync_groups WHERE group_id = ?) - ? + 1 + ?, ?) " +
              "ON CONFLICT (group_id, uid) DO UPDATE SET seq = excluded.seq, blob = excluded.blob",
          )
          .bind(groupId, b.uid, groupId, n, i, b.blob),
      ),
      this.d1.prepare("UPDATE sync_groups SET record_count = (SELECT COUNT(*) FROM sync_records WHERE group_id = ?) WHERE group_id = ?").bind(groupId, groupId),
      this.d1.prepare("SELECT seq FROM sync_groups WHERE group_id = ?").bind(groupId),
    ];
    const results = await this.d1.batch<{ seq: number }>(stmts);
    const seq = results.at(-1)?.results[0]?.seq;
    if (seq == null) throw new SyncError(404, "not_found", "Sync group not found");
    return seq;
  }

  async pull(groupId: string, since: number, limit: number): Promise<StoredBlob[]> {
    const res = await this.d1
      .prepare("SELECT uid, blob, seq FROM sync_records WHERE group_id = ? AND seq > ? ORDER BY seq LIMIT ?")
      .bind(groupId, since, limit)
      .all<StoredBlob>();
    return res.results;
  }

  async markVerified(groupId: string, premiumUntil: number | null): Promise<void> {
    await this.d1.prepare("UPDATE sync_groups SET verified = 1, premium_until = ? WHERE group_id = ?").bind(premiumUntil, groupId).run();
  }

  async deleteGroup(groupId: string): Promise<void> {
    await this.d1.batch([
      this.d1.prepare("DELETE FROM sync_records WHERE group_id = ?").bind(groupId),
      this.d1.prepare("DELETE FROM sync_groups WHERE group_id = ?").bind(groupId),
    ]);
  }
}
