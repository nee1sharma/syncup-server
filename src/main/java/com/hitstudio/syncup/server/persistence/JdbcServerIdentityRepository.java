package com.hitstudio.syncup.server.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcServerIdentityRepository {
	private final JdbcClient jdbc;

	public JdbcServerIdentityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Records.ServerIdentity> find() {
		return jdbc.sql("SELECT server_id, server_name, created_at FROM server_identity LIMIT 1")
				.query((rs, rowNum) -> new Records.ServerIdentity(
						UUID.fromString(rs.getString("server_id")),
						rs.getString("server_name"),
						Instant.parse(rs.getString("created_at"))))
				.optional();
	}

	public void insert(Records.ServerIdentity identity) {
		jdbc.sql("""
				INSERT INTO server_identity(server_id, server_name, created_at)
				VALUES (:id, :name, :created)
				""")
				.param("id", identity.serverId().toString())
				.param("name", identity.serverName())
				.param("created", identity.createdAt().toString())
				.update();
	}
}
