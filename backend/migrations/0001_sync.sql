-- Encrypted sync relay (see src/sync.ts). Apply with:
--   npx wrangler d1 migrations apply piso-sync --remote
CREATE TABLE IF NOT EXISTS sync_groups (
  group_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0,
  premium_until INTEGER,
  record_count INTEGER NOT NULL DEFAULT 0,
  seq INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_records (
  group_id TEXT NOT NULL,
  uid TEXT NOT NULL,
  seq INTEGER NOT NULL,
  blob TEXT NOT NULL,
  PRIMARY KEY (group_id, uid)
);

CREATE INDEX IF NOT EXISTS sync_records_by_seq ON sync_records (group_id, seq);
