package com.hitstudio.syncup.server.backup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BackupDtos {
	private BackupDtos() {
	}

	public record CreateBackupRequest(
			@NotNull UUID deviceId,
			@NotBlank @Size(max = 120) String deviceName,
			@NotBlank @Size(max = 160) String idempotencyKey
	) {
	}

	public record BackupRunResponse(
			UUID runId,
			UUID deviceId,
			String state,
			Instant startedAt,
			Instant completedAt,
			long fileCount,
			long byteCount
	) {
	}

	public record ManifestBatchRequest(
			@NotNull UUID deviceId,
			@NotBlank @Size(max = 120) String deviceName,
			@NotEmpty List<@Valid ManifestFileRequest> files
	) {
	}

	public record ManifestFileRequest(
			@NotBlank @Size(max = 256) String clientFileKey,
			@NotBlank @Size(max = 255) String displayName,
			@Size(max = 1024) String relativePath,
			@NotBlank @Size(max = 40) String mediaType,
			@NotBlank @Size(max = 255) String mimeType,
			long sizeBytes,
			@NotBlank
			@Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "must be a 64-character SHA-256")
			String sha256,
			Instant capturedAt,
			Instant modifiedAt
	) {
	}

	public record ManifestPlanResponse(
			UUID runId,
			String state,
			List<ManifestPlanItem> files
	) {
	}

	public record ManifestPlanItem(
			String clientFileKey,
			String disposition,
			UUID fileId,
			UUID transferId,
			long uploadOffset,
			long segmentBytes,
			String rejectionCode
	) {
	}

	public record RunActionRequest(
			@NotNull UUID deviceId,
			@NotBlank @Size(max = 120) String deviceName
	) {
	}
}
