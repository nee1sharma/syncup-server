package com.hitstudio.syncup.server.persistence;

import java.time.Instant;
import java.util.UUID;

public final class Records {
	private Records() {
	}

	public record ServerIdentity(UUID serverId, String serverName, Instant createdAt) {
	}

	public record BackupRun(
			UUID runId,
			UUID deviceId,
			String idempotencyKey,
			String state,
			Instant startedAt,
			Instant completedAt,
			long fileCount,
			long byteCount
	) {
	}

	public record ManifestEntry(
			UUID runId,
			String clientFileKey,
			String displayName,
			String relativePath,
			String mediaType,
			String mimeType,
			long sizeBytes,
			String sha256,
			Instant capturedAt,
			Instant modifiedAt,
			String disposition
	) {
	}

	public record Transfer(
			UUID transferId,
			UUID runId,
			UUID deviceId,
			String clientFileKey,
			String partialPath,
			long expectedSize,
			String expectedSha256,
			long acceptedOffset,
			String state,
			Instant lastActivityAt,
			Instant expiresAt,
			UUID stagedFileId
	) {
	}

	public record StoredFile(
			UUID fileId,
			UUID deviceId,
			String clientFileKey,
			String originalName,
			String originalRelativePath,
			String mediaType,
			String mimeType,
			long sizeBytes,
			String sha256,
			Instant capturedAt,
			Instant modifiedAt,
			String storedPath,
			Instant backedUpAt,
			String status
	) {
	}
}
