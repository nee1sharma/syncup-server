CREATE TABLE IF NOT EXISTS server_identity (
    server_id TEXT PRIMARY KEY,
    server_name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS backup_runs (
    run_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    idempotency_key TEXT NOT NULL,
    state TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    file_count INTEGER NOT NULL DEFAULT 0,
    byte_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (device_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS manifest_entries (
    run_id TEXT NOT NULL REFERENCES backup_runs(run_id) ON DELETE CASCADE,
    client_file_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    relative_path TEXT,
    media_type TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    captured_at TEXT,
    modified_at TEXT,
    disposition TEXT NOT NULL,
    PRIMARY KEY (run_id, client_file_key)
);

CREATE TABLE IF NOT EXISTS files (
    file_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    client_file_key TEXT NOT NULL,
    original_name TEXT NOT NULL,
    original_relative_path TEXT,
    media_type TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    captured_at TEXT,
    modified_at TEXT,
    stored_path TEXT NOT NULL UNIQUE,
    backed_up_at TEXT NOT NULL,
    status TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_files_committed_identity
    ON files(device_id, sha256, size_bytes) WHERE status = 'COMMITTED';
CREATE INDEX IF NOT EXISTS idx_files_device_captured ON files(device_id, captured_at, file_id);
CREATE INDEX IF NOT EXISTS idx_files_device_media ON files(device_id, media_type, file_id);
CREATE INDEX IF NOT EXISTS idx_files_status ON files(status);

CREATE TABLE IF NOT EXISTS transfers (
    transfer_id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES backup_runs(run_id),
    device_id TEXT NOT NULL REFERENCES devices(device_id),
    client_file_key TEXT NOT NULL,
    partial_path TEXT NOT NULL UNIQUE,
    expected_size INTEGER NOT NULL,
    expected_sha256 TEXT NOT NULL,
    accepted_offset INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL,
    last_activity_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    staged_file_id TEXT REFERENCES files(file_id)
);

CREATE INDEX IF NOT EXISTS idx_transfers_run_key ON transfers(run_id, client_file_key);
CREATE INDEX IF NOT EXISTS idx_transfers_identity
    ON transfers(device_id, expected_sha256, expected_size, state);
CREATE INDEX IF NOT EXISTS idx_transfers_expiry ON transfers(state, expires_at);
