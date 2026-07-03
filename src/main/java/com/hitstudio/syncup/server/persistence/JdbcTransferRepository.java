package com.hitstudio.syncup.server.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferRepository {
	private final JdbcClient jdbc;

	public JdbcTransferRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Records.Transfer transfer) {
		jdbc.sql("""
				INSERT INTO transfers(
				  transfer_id, run_id, device_id, client_file_key, partial_path,
				  expected_size, expected_sha256, accepted_offset, state,
				  last_activity_at, expires_at, staged_file_id)
				VALUES (
				  :id, :runId, :deviceId, :key, :path,
				  :size, :sha, :offset, :state, :activity, :expires, NULL)
				""")
				.param("id", transfer.transferId().toString())
				.param("runId", transfer.runId().toString())
				.param("deviceId", transfer.deviceId().toString())
				.param("key", transfer.clientFileKey())
				.param("path", transfer.partialPath())
				.param("size", transfer.expectedSize())
				.param("sha", transfer.expectedSha256())
				.param("offset", transfer.acceptedOffset())
				.param("state", transfer.state())
				.param("activity", transfer.lastActivityAt().toString())
				.param("expires", transfer.expiresAt().toString())
				.update();
	}

	public Optional<Records.Transfer> find(UUID transferId) {
		return jdbc.sql(SELECT + " WHERE transfer_id = :id")
				.param("id", transferId.toString())
				.query(this::map)
				.optional();
	}

	public Optional<Records.Transfer> findForManifest(UUID runId, String key) {
		return jdbc.sql(SELECT + """
				 WHERE run_id = :runId AND client_file_key = :key
				   AND state IN ('PENDING', 'PARTIAL', 'VERIFYING', 'COMMITTED')
				 ORDER BY last_activity_at DESC LIMIT 1
				""")
				.param("runId", runId.toString())
				.param("key", key)
				.query(this::map)
				.optional();
	}

	public void updateOffset(UUID id, long offset, String state, Instant activity, Instant expires) {
		jdbc.sql("""
				UPDATE transfers
				SET accepted_offset = :offset, state = :state,
				    last_activity_at = :activity, expires_at = :expires
				WHERE transfer_id = :id
				""")
				.param("offset", offset)
				.param("state", state)
				.param("activity", activity.toString())
				.param("expires", expires.toString())
				.param("id", id.toString())
				.update();
	}

	public void stageFile(UUID transferId, UUID fileId) {
		jdbc.sql("""
				UPDATE transfers SET state = 'VERIFYING', staged_file_id = :fileId
				WHERE transfer_id = :id
				""")
				.param("fileId", fileId.toString())
				.param("id", transferId.toString())
				.update();
	}

	public void markCommitted(UUID transferId, Instant now) {
		jdbc.sql("""
				UPDATE transfers
				SET state = 'COMMITTED', last_activity_at = :now, accepted_offset = expected_size
				WHERE transfer_id = :id
				""")
				.param("now", now.toString())
				.param("id", transferId.toString())
				.update();
	}

	public void markRejected(UUID transferId, Instant now) {
		jdbc.sql("""
				UPDATE transfers SET state = 'REJECTED', last_activity_at = :now
				WHERE transfer_id = :id
				""")
				.param("now", now.toString())
				.param("id", transferId.toString())
				.update();
	}

	public void resetStaging(UUID transferId, long offset, Instant now, Instant expires) {
		jdbc.sql("""
				UPDATE transfers
				SET state = CASE WHEN :offset = 0 THEN 'PENDING' ELSE 'PARTIAL' END,
				    accepted_offset = :offset, staged_file_id = NULL,
				    last_activity_at = :now, expires_at = :expires
				WHERE transfer_id = :id
				""")
				.param("offset", offset)
				.param("now", now.toString())
				.param("expires", expires.toString())
				.param("id", transferId.toString())
				.update();
	}

	public List<Records.Transfer> findRecoverable() {
		return jdbc.sql(SELECT + " WHERE state IN ('PENDING', 'PARTIAL', 'VERIFYING')")
				.query(this::map)
				.list();
	}

	public List<Records.Transfer> findExpired(Instant now) {
		return jdbc.sql(SELECT + """
				 WHERE state IN ('PENDING', 'PARTIAL', 'REJECTED')
				   AND expires_at < :now
				""")
				.param("now", now.toString())
				.query(this::map)
				.list();
	}

	public void expire(UUID id) {
		jdbc.sql("UPDATE transfers SET state = 'EXPIRED' WHERE transfer_id = :id")
				.param("id", id.toString())
				.update();
	}

	public List<String> allPartialPaths() {
		return jdbc.sql("SELECT partial_path FROM transfers")
				.query(String.class)
				.list();
	}

	private Records.Transfer map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		String stagedId = rs.getString("staged_file_id");
		return new Records.Transfer(
				UUID.fromString(rs.getString("transfer_id")),
				UUID.fromString(rs.getString("run_id")),
				UUID.fromString(rs.getString("device_id")),
				rs.getString("client_file_key"),
				rs.getString("partial_path"),
				rs.getLong("expected_size"),
				rs.getString("expected_sha256"),
				rs.getLong("accepted_offset"),
				rs.getString("state"),
				Instant.parse(rs.getString("last_activity_at")),
				Instant.parse(rs.getString("expires_at")),
				stagedId == null ? null : UUID.fromString(stagedId));
	}

	private static final String SELECT = """
			SELECT transfer_id, run_id, device_id, client_file_key, partial_path,
			       expected_size, expected_sha256, accepted_offset, state,
			       last_activity_at, expires_at, staged_file_id
			FROM transfers
			""";
}
