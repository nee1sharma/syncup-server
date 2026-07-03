package com.hitstudio.syncup.server.discovery;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.persistence.JdbcServerIdentityRepository;
import com.hitstudio.syncup.server.persistence.Records;
import com.hitstudio.syncup.server.support.StorageBootstrap;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.UUID;

@Service
@DependsOn("flyway")
public class ServerIdentityService {
	private final JdbcServerIdentityRepository repository;
	private final SyncUpProperties properties;
	private final StorageBootstrap storage;
	private final Clock clock;
	private Records.ServerIdentity identity;

	public ServerIdentityService(
			JdbcServerIdentityRepository repository,
			SyncUpProperties properties,
			StorageBootstrap storage,
			Clock clock
	) {
		this.repository = repository;
		this.properties = properties;
		this.storage = storage;
		this.clock = clock;
	}

	@PostConstruct
	@Transactional
	void initialize() throws IOException {
		Path identityFile = storage.root().resolve("server-id");
		UUID fileId = readIdentityFile(identityFile);
		var persisted = repository.find();
		if (persisted.isPresent()) {
			identity = persisted.get();
			if (fileId != null && !identity.serverId().equals(fileId)) {
				throw new IllegalStateException("server-id file does not match SQLite identity");
			}
			if (fileId == null) {
				writeIdentityFile(identityFile, identity.serverId());
			}
			return;
		}

		UUID serverId = fileId == null ? UUID.randomUUID() : fileId;
		identity = new Records.ServerIdentity(
				serverId, properties.server().name().trim(), clock.instant());
		repository.insert(identity);
		if (fileId == null) {
			writeIdentityFile(identityFile, serverId);
		}
	}

	public Records.ServerIdentity identity() {
		return identity;
	}

	private UUID readIdentityFile(Path path) throws IOException {
		if (!Files.exists(path)) {
			return null;
		}
		try {
			return UUID.fromString(Files.readString(path, StandardCharsets.UTF_8).trim());
		} catch (IllegalArgumentException invalid) {
			throw new IllegalStateException("server-id contains an invalid UUID", invalid);
		}
	}

	private void writeIdentityFile(Path path, UUID id) throws IOException {
		Files.writeString(path, id + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}
}
