import { describe, expect, it } from "vitest";
import { LIMITS, pull, push, sha256Hex, SyncError, type StoredBlob, type SyncDb, type SyncGroup } from "../src/sync";

/** In-memory stand-in for D1 with the same semantics as D1SyncDb. */
class MemoryDb implements SyncDb {
  groups = new Map<string, SyncGroup>();
  records = new Map<string, Map<string, StoredBlob>>();
  async getGroup(id: string) { return this.groups.get(id) ?? null; }
  async createGroup(id: string, tokenHash: string, verified: boolean) {
    if (!this.groups.has(id)) this.groups.set(id, { groupId: id, tokenHash, verified, premiumUntil: null, recordCount: 0, seq: 0 });
  }
  async upsert(id: string, blobs: Array<{ uid: string; blob: string }>) {
    const g = this.groups.get(id)!;
    const recs = this.records.get(id) ?? new Map<string, StoredBlob>();
    for (const b of blobs) recs.set(b.uid, { uid: b.uid, blob: b.blob, seq: ++g.seq });
    this.records.set(id, recs);
    g.recordCount = recs.size;
    return g.seq;
  }
  async pull(id: string, since: number, limit: number) {
    return [...(this.records.get(id)?.values() ?? [])].filter((r) => r.seq > since).sort((a, b) => a.seq - b.seq).slice(0, limit);
  }
  async markVerified(id: string, premiumUntil: number | null) {
    const g = this.groups.get(id)!;
    g.verified = true;
    g.premiumUntil = premiumUntil;
  }
  async deleteGroup(id: string) { this.groups.delete(id); this.records.delete(id); }
}

const auth = { groupId: "0123456789abcdef0123456789abcdef", token: "A".repeat(43) };
const phone = { hasSession: true, premiumUntil: null, enforce: true };
const desktop = { hasSession: false, premiumUntil: null, enforce: true };

describe("sync relay", () => {
  it("lets a verified phone create a group, then any member push and pull", async () => {
    const db = new MemoryDb();
    await expect(push(db, auth, { records: [{ uid: "a", blob: "x" }] }, desktop)).rejects.toMatchObject({ code: "group_not_registered" });

    const first = await push(db, auth, { records: [{ uid: "a", blob: "x1" }, { uid: "b", blob: "y1" }] }, phone);
    expect(first).toEqual({ stored: 2, cursor: 2 });
    expect(db.groups.get(auth.groupId)?.tokenHash).toBe(await sha256Hex(auth.token));
    expect(db.groups.get(auth.groupId)?.verified).toBe(true);

    // The desktop can now push to the existing group, and an update gets a new sequence.
    await push(db, auth, { records: [{ uid: "a", blob: "x2" }] }, desktop);
    const page = await pull(db, auth, 2, desktop);
    expect(page.records).toEqual([{ uid: "a", blob: "x2", seq: 3 }]);
    expect(page.cursor).toBe(3);
  });

  it("rejects the wrong token and oversized input", async () => {
    const db = new MemoryDb();
    await push(db, auth, { records: [] }, phone);
    await expect(push(db, { ...auth, token: "B".repeat(43) }, { records: [] }, phone)).rejects.toMatchObject({ status: 403 });
    await expect(push(db, auth, { records: [{ uid: "a", blob: "x".repeat(LIMITS.blobChars + 1) }] }, phone)).rejects.toBeInstanceOf(SyncError);
    await expect(push(db, auth, { records: [{ uid: "bad uid!", blob: "x" }] }, phone)).rejects.toBeInstanceOf(SyncError);
  });

  it("pages long pulls", async () => {
    const db = new MemoryDb();
    const records = Array.from({ length: LIMITS.recordsPerPush }, (_, i) => ({ uid: `r${i}`, blob: "x" }));
    await push(db, auth, { records }, phone);
    await push(db, auth, { records: records.map((r) => ({ ...r, uid: r.uid + "b" })) }, phone);
    const first = await pull(db, auth, 0, desktop);
    expect(first.records).toHaveLength(LIMITS.pullPage);
    expect(first.more).toBe(true);
    const second = await pull(db, auth, first.cursor, desktop);
    expect(second.more).toBe(false);
    expect(first.records.length + second.records.length).toBe(LIMITS.recordsPerPush * 2);
  });

  it("records the phone's premium status on the group for desktops", async () => {
    const db = new MemoryDb();
    await push(db, auth, { records: [] }, phone);
    await pull(db, auth, 0, { ...phone, premiumUntil: 9_999_999_999_999 });
    expect(db.groups.get(auth.groupId)?.premiumUntil).toBe(9_999_999_999_999);
    // A desktop without a session must not clear it.
    await pull(db, auth, 0, desktop);
    expect(db.groups.get(auth.groupId)?.premiumUntil).toBe(9_999_999_999_999);
  });
});
