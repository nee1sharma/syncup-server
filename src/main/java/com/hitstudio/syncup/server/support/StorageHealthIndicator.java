package com.hitstudio.syncup.server.support;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.persistence.JdbcFileRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

@Component
public class StorageHealthIndicator implements HealthIndicator {
	private final StorageBootstrap storage;
	private final SyncUpProperties properties;
	private final JdbcFileRepository files;

	public StorageHealthIndicator(
			StorageBootstrap storage,
			SyncUpProperties properties,
			JdbcFileRepository files
	) {
		this.storage = storage;
		this.properties = properties;
		this.files = files;
	}

	@Override
	public Health health() {
		try {
			long free = storage.usableSpace();
			long missing = files.missingCount();
			if (!Files.isWritable(storage.root())
					|| free < properties.storage().minimumFreeBytes()
					|| missing > 0) {
				return Health.down()
						.withDetail("writable", Files.isWritable(storage.root()))
						.withDetail("minimumFreeBytesSatisfied",
								free >= properties.storage().minimumFreeBytes())
						.withDetail("missingCommittedFiles", missing)
						.build();
			}
			return Health.up().build();
		} catch (Exception exception) {
			return Health.down().build();
		}
	}
}
