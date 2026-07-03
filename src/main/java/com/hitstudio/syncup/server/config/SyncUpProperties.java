package com.hitstudio.syncup.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "syncup")
public record SyncUpProperties(
		@NotNull @Valid Server server,
		@NotNull @Valid Discovery discovery,
		@NotNull @Valid Storage storage,
		@NotNull @Valid Transfer transfer,
		@NotNull @Valid Manifest manifest
) {
	public record Server(@NotBlank String name) {
	}

	public record Discovery(
			boolean enabled,
			@Min(1) @Max(65535) int port,
			@Min(256) @Max(65507) int maxDatagramBytes
	) {
	}

	public record Storage(
			@NotNull Path root,
			@Min(0) long minimumFreeBytes,
			@NotNull Duration partialRetention
	) {
		public Storage {
			if (partialRetention != null && (partialRetention.isZero() || partialRetention.isNegative())) {
				throw new IllegalArgumentException("syncup.storage.partial-retention must be positive");
			}
		}
	}

	public record Transfer(
			@Min(1) long segmentBytes,
			@Min(1) long maxSegmentBytes,
			@Min(1) long maxFileBytes,
			@Min(1) int maxConcurrentPerDevice,
			@Min(1) int maxConcurrentTotal,
			@NotNull Duration idleTimeout
	) {
		public Transfer {
			if (segmentBytes > maxSegmentBytes) {
				throw new IllegalArgumentException("syncup.transfer.segment-bytes cannot exceed max-segment-bytes");
			}
			if (idleTimeout != null && (idleTimeout.isZero() || idleTimeout.isNegative())) {
				throw new IllegalArgumentException("syncup.transfer.idle-timeout must be positive");
			}
		}
	}

	public record Manifest(
			@Min(1) @Max(10_000) int maxBatchFiles,
			@Min(1024) long maxBodyBytes
	) {
	}
}
