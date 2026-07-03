package com.hitstudio.syncup.server.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBackupRepository {
	private final JdbcClient jdbc;

	public JdbcBackupRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void upsertDevice(UUID id, String name, Instant now) {
		jdbc.sql("""
				INSERT INTO devices(device_id, device_name, first_seen_at, last_seen_at)
				VALUES (:id, :name, :now, :now)
				ON CONFLICT(device_id) DO UPDATE SET
				  device_name = excluded.device_name,
				  last_seen_at = excluded.last_seen_at
				""")
				.param("id", id.toString())
				.param("name", name)
				.param("now", now.toString())
				.update();
	}

	public Optional<Records.BackupRun> findRunById(UUID runId) {
		return jdbc.sql("""
				SELECT run_id, device_id, idempotency_key, state, started_at, completed_at,
				       file_count, byte_count
				FROM backup_runs WHERE run_id = :runId
				""")
				.param("runId", runId.toString())
				.query(this::mapRun)
				.optional();
	}

	public Optional<Records.BackupRun> findRunByIdempotency(UUID deviceId, String key) {
		return jdbc.sql("""
				SELECT run_id, device_id, idempotency_key, state, started_at, completed_at,
				       file_count, byte_count
				FROM backup_runs
				WHERE device_id = :deviceId AND idempotency_key = :key
				""")
				.param("deviceId", deviceId.toString())
				.param("key", key)
				.query(this::mapRun)
				.optional();
	}

	public void insertRun(Records.BackupRun run) {
		jdbc.sql("""
				INSERT INTO backup_runs(
				  run_id, device_id, idempotency_key, state, started_at,
				  completed_at, file_count, byte_count)
				VALUES (:runId, :deviceId, :key, :state, :started, NULL, 0, 0)
				""")
				.param("runId", run.runId().toString())
				.param("deviceId", run.deviceId().toString())
				.param("key", run.idempotencyKey())
				.param("state", run.state())
				.param("started", run.startedAt().toString())
				.update();
	}

	public boolean manifestEntryExists(UUID runId, String clientFileKey) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM manifest_entries
				WHERE run_id = :runId AND client_file_key = :key
				""")
				.param("runId", runId.toString())
				.param("key", clientFileKey)
				.query(Long.class)
				.single() > 0;
	}

	public void insertManifestEntry(Records.ManifestEntry entry) {
		jdbc.sql("""
				INSERT INTO manifest_entries(
				  run_id, client_file_key, display_name, relative_path, media_type, mime_type,
				  size_bytes, sha256, captured_at, modified_at, disposition)
				VALUES (
				  :runId, :key, :name, :path, :media, :mime,
				  :size, :sha, :captured, :modified, :disposition)
				""")
				.param("runId", entry.runId().toString())
				.param("key", entry.clientFileKey())
				.param("name", entry.displayName())
				.param("path", entry.relativePath())
				.param("media", entry.mediaType())
				.param("mime", entry.mimeType())
				.param("size", entry.sizeBytes())
				.param("sha", entry.sha256())
				.param("captured", text(entry.capturedAt()))
				.param("modified", text(entry.modifiedAt()))
				.param("disposition", entry.disposition())
				.update();
	}

	public Optional<Records.ManifestEntry> findManifestEntry(UUID runId, String clientFileKey) {
		return jdbc.sql("""
				SELECT run_id, client_file_key, display_name, relative_path, media_type, mime_type,
				       size_bytes, sha256, captured_at, modified_at, disposition
				FROM manifest_entries
				WHERE run_id = :runId AND client_file_key = :key
				""")
				.param("runId", runId.toString())
				.param("key", clientFileKey)
				.query((rs, rowNum) -> new Records.ManifestEntry(
						UUID.fromString(rs.getString("run_id")),
						rs.getString("client_file_key"),
						rs.getString("display_name"),
						rs.getString("relative_path"),
						rs.getString("media_type"),
						rs.getString("mime_type"),
						rs.getLong("size_bytes"),
						rs.getString("sha256"),
						parse(rs.getString("captured_at")),
						parse(rs.getString("modified_at")),
						rs.getString("disposition")))
				.optional();
	}

	public void updateRunState(UUID runId, String state) {
		jdbc.sql("UPDATE backup_runs SET state = :state WHERE run_id = :runId")
				.param("state", state)
				.param("runId", runId.toString())
				.update();
	}

	public void completeRun(UUID runId, Instant now) {
		jdbc.sql("""
				UPDATE backup_runs
				SET state = 'COMPLETED',
				    completed_at = :now,
				    file_count = (
				      SELECT COUNT(*) FROM manifest_entries
				      WHERE run_id = :runId AND disposition <> 'REJECTED'
				    ),
				    byte_count = COALESCE((
				      SELECT SUM(size_bytes) FROM manifest_entries
				      WHERE run_id = :runId AND disposition <> 'REJECTED'
				    ), 0)
				WHERE run_id = :runId
				""")
				.param("now", now.toString())
				.param("runId", runId.toString())
				.update();
	}

	public long countUncommittedTransfers(UUID runId) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM transfers
				WHERE run_id = :runId AND state <> 'COMMITTED'
				""")
				.param("runId", runId.toString())
				.query(Long.class)
				.single();
	}

	public void interruptActiveRuns() {
		jdbc.sql("""
				UPDATE backup_runs SET state = 'INTERRUPTED'
				WHERE state IN ('PREPARING', 'PLANNED', 'TRANSFERRING')
				""").update();
	}

	private Records.BackupRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		String completed = rs.getString("completed_at");
		return new Records.BackupRun(
				UUID.fromString(rs.getString("run_id")),
				UUID.fromString(rs.getString("device_id")),
				rs.getString("idempotency_key"),
				rs.getString("state"),
				Instant.parse(rs.getString("started_at")),
				completed == null ? null : Instant.parse(completed),
				rs.getLong("file_count"),
				rs.getLong("byte_count"));
	}

	private String text(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	private Instant parse(String instant) {
		return instant == null ? null : Instant.parse(instant);
	}
}
