package com.hitstudio.syncup.server.backup;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.persistence.JdbcBackupRepository;
import com.hitstudio.syncup.server.persistence.JdbcFileRepository;
import com.hitstudio.syncup.server.persistence.JdbcTransferRepository;
import com.hitstudio.syncup.server.persistence.Records;
import com.hitstudio.syncup.server.support.DomainException;
import com.hitstudio.syncup.server.support.StorageBootstrap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class BackupService {
	private static final Set<String> SUPPORTED_MEDIA_TYPES =
			Set.of("IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "OTHER");
	private static final Set<String> TERMINAL_STATES =
			Set.of("COMPLETED", "CANCELLED", "FAILED");

	private final JdbcBackupRepository backups;
	private final JdbcFileRepository files;
	private final JdbcTransferRepository transfers;
	private final SyncUpProperties properties;
	private final StorageBootstrap storage;
	private final Clock clock;

	public BackupService(
			JdbcBackupRepository backups,
			JdbcFileRepository files,
			JdbcTransferRepository transfers,
			SyncUpProperties properties,
			StorageBootstrap storage,
			Clock clock
	) {
		this.backups = backups;
		this.files = files;
		this.transfers = transfers;
		this.properties = properties;
		this.storage = storage;
		this.clock = clock;
	}

	@Transactional
	public BackupDtos.BackupRunResponse create(BackupDtos.CreateBackupRequest request) {
		Instant now = clock.instant();
		backups.upsertDevice(request.deviceId(), request.deviceName().trim(), now);
		var existing = backups.findRunByIdempotency(request.deviceId(), request.idempotencyKey());
		if (existing.isPresent()) {
			return response(existing.get());
		}

		Records.BackupRun run = new Records.BackupRun(
				UUID.randomUUID(), request.deviceId(), request.idempotencyKey(),
				"PREPARING", now, null, 0, 0);
		try {
			backups.insertRun(run);
		} catch (DataIntegrityViolationException race) {
			return response(backups.findRunByIdempotency(request.deviceId(), request.idempotencyKey())
					.orElseThrow(() -> race));
		}
		return response(run);
	}

	@Transactional
	public BackupDtos.ManifestPlanResponse submitManifest(
			UUID runId, BackupDtos.ManifestBatchRequest request
	) {
		backups.upsertDevice(request.deviceId(), request.deviceName().trim(), clock.instant());
		if (request.files().size() > properties.manifest().maxBatchFiles()) {
			throw new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "MANIFEST_BATCH_TOO_LARGE",
					"Manifest batch exceeds the configured file limit");
		}
		Records.BackupRun run = ownedRun(runId, request.deviceId());
		if (TERMINAL_STATES.contains(run.state())) {
			throw new DomainException(HttpStatus.CONFLICT, "RUN_NOT_ACTIVE",
					"The backup run no longer accepts manifest entries");
		}

		HashSet<String> batchKeys = new HashSet<>();
		for (var item : request.files()) {
			if (!batchKeys.add(item.clientFileKey())) {
				throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_CLIENT_FILE_KEY",
						"A clientFileKey occurs more than once in the manifest batch");
			}
			validateDescriptor(item);
			if (backups.manifestEntryExists(runId, item.clientFileKey())) {
				throw new DomainException(HttpStatus.CONFLICT, "DUPLICATE_CLIENT_FILE_KEY",
						"The backup run already contains this clientFileKey");
			}
		}

		ArrayList<BackupDtos.ManifestPlanItem> plan = new ArrayList<>(request.files().size());
		for (var item : request.files()) {
			String sha = item.sha256().toLowerCase(Locale.ROOT);
			var committed = files.findCommittedIdentity(request.deviceId(), sha, item.sizeBytes());
			if (committed.isPresent()) {
				insertManifest(runId, item, sha, "PRESENT");
				plan.add(new BackupDtos.ManifestPlanItem(
						item.clientFileKey(), "PRESENT", committed.get().fileId(),
						null, item.sizeBytes(), properties.transfer().segmentBytes(), null));
				continue;
			}

			ensureStorageBudget(item.sizeBytes());
			Records.Transfer transfer = transfers.findForManifest(runId, item.clientFileKey())
					.filter(current -> current.expectedSize() == item.sizeBytes()
							&& current.expectedSha256().equals(sha))
					.orElseGet(() -> newTransfer(run, item, sha, request.deviceName()));
			String disposition = transfer.acceptedOffset() > 0 ? "RESUME" : "UPLOAD";
			insertManifest(runId, item, sha, disposition);
			plan.add(new BackupDtos.ManifestPlanItem(
					item.clientFileKey(), disposition, null, transfer.transferId(),
					transfer.acceptedOffset(), properties.transfer().segmentBytes(), null));
		}
		backups.updateRunState(runId, "PLANNED");
		return new BackupDtos.ManifestPlanResponse(runId, "PLANNED", plan);
	}

	@Transactional
	public BackupDtos.BackupRunResponse complete(UUID runId, UUID deviceId, String deviceName) {
		backups.upsertDevice(deviceId, deviceName.trim(), clock.instant());
		Records.BackupRun run = ownedRun(runId, deviceId);
		if ("COMPLETED".equals(run.state())) {
			return response(run);
		}
		if ("CANCELLED".equals(run.state())) {
			throw new DomainException(HttpStatus.CONFLICT, "RUN_CANCELLED",
					"A cancelled backup run cannot be completed");
		}
		if (backups.countUncommittedTransfers(runId) > 0) {
			throw new DomainException(HttpStatus.CONFLICT, "TRANSFERS_INCOMPLETE",
					"All planned uploads must be committed before completing the run");
		}
		backups.completeRun(runId, clock.instant());
		return response(backups.findRunById(runId).orElseThrow());
	}

	@Transactional
	public BackupDtos.BackupRunResponse cancel(UUID runId, UUID deviceId, String deviceName) {
		backups.upsertDevice(deviceId, deviceName.trim(), clock.instant());
		Records.BackupRun run = ownedRun(runId, deviceId);
		if ("COMPLETED".equals(run.state())) {
			throw new DomainException(HttpStatus.CONFLICT, "RUN_COMPLETED",
					"A completed backup run cannot be cancelled");
		}
		backups.updateRunState(runId, "CANCELLED");
		return response(backups.findRunById(runId).orElseThrow());
	}

	public Records.BackupRun ownedRun(UUID runId, UUID deviceId) {
		Records.BackupRun run = backups.findRunById(runId)
				.orElseThrow(() -> new DomainException(
						HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Backup run was not found"));
		if (!run.deviceId().equals(deviceId)) {
			throw new DomainException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
					"Backup run was not found for this device");
		}
		return run;
	}

	private Records.Transfer newTransfer(
			Records.BackupRun run, BackupDtos.ManifestFileRequest item, String sha, String deviceName
	) {
		Instant now = clock.instant();
		UUID id = UUID.randomUUID();
		Records.Transfer transfer = new Records.Transfer(
				id, run.runId(), run.deviceId(), item.clientFileKey(),
				Path.of("partial", storageFolderName(deviceName), run.runId().toString(),
						id + ".part").toString().replace('\\', '/'),
				item.sizeBytes(), sha, 0, "PENDING", now,
				now.plus(properties.storage().partialRetention()), null);
		transfers.insert(transfer);
		return transfer;
	}

	private String storageFolderName(String deviceName) {
		String value = deviceName == null ? "" : deviceName.trim();
		String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_")
				.replaceAll("_+", "_");
		if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
			return "device";
		}
		return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180);
	}

	private void insertManifest(
			UUID runId, BackupDtos.ManifestFileRequest item, String sha, String disposition
	) {
		backups.insertManifestEntry(new Records.ManifestEntry(
				runId, item.clientFileKey(), item.displayName(), item.relativePath(),
				item.mediaType().toUpperCase(Locale.ROOT), item.mimeType(),
				item.sizeBytes(), sha, item.capturedAt(), item.modifiedAt(), disposition));
	}

	private void validateDescriptor(BackupDtos.ManifestFileRequest item) {
		if (item.sizeBytes() < 0 || item.sizeBytes() > properties.transfer().maxFileBytes()) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE",
					"File size is outside the supported range");
		}
		if (!SUPPORTED_MEDIA_TYPES.contains(item.mediaType().toUpperCase(Locale.ROOT))) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE",
					"Unsupported logical mediaType");
		}
		if (hasControl(item.displayName()) || item.displayName().contains("/")
				|| item.displayName().contains("\\")) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME",
					"displayName must be a plain file name without path separators");
		}
		String relative = item.relativePath();
		if (relative != null && !relative.isBlank()) {
			if (hasControl(relative) || relative.startsWith("/") || relative.startsWith("\\")
					|| relative.matches("^[A-Za-z]:.*")) {
				throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_RELATIVE_PATH",
						"relativePath must be a safe relative path");
			}
			for (String segment : relative.replace('\\', '/').split("/")) {
				if ("..".equals(segment) || ".".equals(segment)) {
					throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_RELATIVE_PATH",
							"relativePath cannot contain traversal segments");
				}
			}
		}
		if (hasControl(item.mimeType()) || !item.mimeType().contains("/")) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_MIME_TYPE",
					"mimeType is invalid");
		}
	}

	private void ensureStorageBudget(long incomingBytes) {
		try {
			long required = Math.addExact(properties.storage().minimumFreeBytes(), incomingBytes);
			if (storage.usableSpace() < required) {
				throw new DomainException(HttpStatus.INSUFFICIENT_STORAGE, "INSUFFICIENT_STORAGE",
						"Storage safety margin would be exceeded");
			}
		} catch (ArithmeticException | IOException exception) {
			throw new DomainException(HttpStatus.INSUFFICIENT_STORAGE, "STORAGE_UNAVAILABLE",
					"Storage capacity could not be confirmed");
		}
	}

	private boolean hasControl(String value) {
		return value.codePoints().anyMatch(Character::isISOControl);
	}

	private BackupDtos.BackupRunResponse response(Records.BackupRun run) {
		return new BackupDtos.BackupRunResponse(
				run.runId(), run.deviceId(), run.state(), run.startedAt(),
				run.completedAt(), run.fileCount(), run.byteCount());
	}
}
