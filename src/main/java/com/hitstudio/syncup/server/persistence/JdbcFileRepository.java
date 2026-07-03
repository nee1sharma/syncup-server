package com.hitstudio.syncup.server.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcFileRepository {
	private final JdbcClient jdbc;

	public JdbcFileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Records.StoredFile> findCommittedIdentity(UUID deviceId, String sha, long size) {
		return jdbc.sql(SELECT + """
				 WHERE device_id = :deviceId AND sha256 = :sha
				   AND size_bytes = :size AND status = 'COMMITTED'
				 LIMIT 1
				""")
				.param("deviceId", deviceId.toString())
				.param("sha", sha)
				.param("size", size)
				.query(this::map)
				.optional();
	}

	public Optional<Records.StoredFile> find(UUID fileId) {
		return jdbc.sql(SELECT + " WHERE file_id = :id")
				.param("id", fileId.toString())
				.query(this::map)
				.optional();
	}

	public void insertStaged(Records.StoredFile file) {
		jdbc.sql("""
				INSERT INTO files(
				  file_id, device_id, client_file_key, original_name, original_relative_path,
				  media_type, mime_type, size_bytes, sha256, captured_at, modified_at,
				  stored_path, backed_up_at, status)
				VALUES (
				  :id, :deviceId, :key, :name, :sourcePath,
				  :media, :mime, :size, :sha, :captured, :modified,
				  :storedPath, :backedUp, :status)
				""")
				.param("id", file.fileId().toString())
				.param("deviceId", file.deviceId().toString())
				.param("key", file.clientFileKey())
				.param("name", file.originalName())
				.param("sourcePath", file.originalRelativePath())
				.param("media", file.mediaType())
				.param("mime", file.mimeType())
				.param("size", file.sizeBytes())
				.param("sha", file.sha256())
				.param("captured", text(file.capturedAt()))
				.param("modified", text(file.modifiedAt()))
				.param("storedPath", file.storedPath())
				.param("backedUp", file.backedUpAt().toString())
				.param("status", file.status())
				.update();
	}

	public void markCommitted(UUID fileId) {
		jdbc.sql("UPDATE files SET status = 'COMMITTED' WHERE file_id = :id")
				.param("id", fileId.toString())
				.update();
	}

	public void markMissing(UUID fileId) {
		jdbc.sql("UPDATE files SET status = 'MISSING' WHERE file_id = :id")
				.param("id", fileId.toString())
				.update();
	}

	public void markDeleted(UUID fileId) {
		jdbc.sql("UPDATE files SET status = 'DELETED' WHERE file_id = :id")
				.param("id", fileId.toString())
				.update();
	}

	public List<Records.StoredFile> listCommitted(
			UUID deviceId, String mediaType, Instant capturedAfter, Instant cursorTime,
			UUID cursorId, int limit
	) {
		return jdbc.sql(SELECT + """
				 WHERE status = 'COMMITTED'
				   AND (:deviceId IS NULL OR device_id = :deviceId)
				   AND (:mediaType IS NULL OR media_type = :mediaType)
				   AND (:capturedAfter IS NULL OR captured_at >= :capturedAfter)
				   AND (:cursorTime IS NULL OR backed_up_at < :cursorTime
				        OR (backed_up_at = :cursorTime AND file_id < :cursorId))
				 ORDER BY backed_up_at DESC, file_id DESC
				 LIMIT :limit
				""")
				.param("deviceId", deviceId == null ? null : deviceId.toString())
				.param("mediaType", mediaType)
				.param("capturedAfter", text(capturedAfter))
				.param("cursorTime", text(cursorTime))
				.param("cursorId", cursorId == null ? null : cursorId.toString())
				.param("limit", limit)
				.query(this::map)
				.list();
	}

	public List<Records.StoredFile> findByStatus(String status) {
		return jdbc.sql(SELECT + " WHERE status = :status")
				.param("status", status)
				.query(this::map)
				.list();
	}

	public long missingCount() {
		return jdbc.sql("SELECT COUNT(*) FROM files WHERE status = 'MISSING'")
				.query(Long.class)
				.single();
	}

	private Records.StoredFile map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Records.StoredFile(
				UUID.fromString(rs.getString("file_id")),
				UUID.fromString(rs.getString("device_id")),
				rs.getString("client_file_key"),
				rs.getString("original_name"),
				rs.getString("original_relative_path"),
				rs.getString("media_type"),
				rs.getString("mime_type"),
				rs.getLong("size_bytes"),
				rs.getString("sha256"),
				instant(rs.getString("captured_at")),
				instant(rs.getString("modified_at")),
				rs.getString("stored_path"),
				Instant.parse(rs.getString("backed_up_at")),
				rs.getString("status"));
	}

	private Instant instant(String text) {
		return text == null ? null : Instant.parse(text);
	}

	private String text(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	private static final String SELECT = """
			SELECT file_id, device_id, client_file_key, original_name, original_relative_path,
			       media_type, mime_type, size_bytes, sha256, captured_at, modified_at,
			       stored_path, backed_up_at, status
			FROM files
			""";
}
