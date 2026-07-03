package com.hitstudio.syncup.server.transfer;

import com.hitstudio.syncup.server.backup.BackupService;
import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.persistence.JdbcBackupRepository;
import com.hitstudio.syncup.server.persistence.JdbcFileRepository;
import com.hitstudio.syncup.server.persistence.JdbcTransferRepository;
import com.hitstudio.syncup.server.persistence.Records;
import com.hitstudio.syncup.server.support.DomainException;
import com.hitstudio.syncup.server.support.StorageBootstrap;
import com.hitstudio.syncup.server.support.SyncUpMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class TransferService {
	private final JdbcTransferRepository transfers;
	private final JdbcBackupRepository backups;
	private final JdbcFileRepository files;
	private final BackupService backupService;
	private final SyncUpProperties properties;
	private final StorageBootstrap storage;
	private final Clock clock;
	private final Executor hashExecutor;
	private final TransactionTemplate transactions;
	private final SyncUpMetrics metrics;
	private final Semaphore globalCapacity;
	private final ConcurrentHashMap<UUID, Semaphore> deviceCapacity = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, ReentrantLock> transferLocks = new ConcurrentHashMap<>();

	public TransferService(
			JdbcTransferRepository transfers,
			JdbcBackupRepository backups,
			JdbcFileRepository files,
			BackupService backupService,
			SyncUpProperties properties,
			StorageBootstrap storage,
			Clock clock,
			@Qualifier("hashExecutor") Executor hashExecutor,
			TransactionTemplate transactions,
			SyncUpMetrics metrics
	) {
		this.transfers = transfers;
		this.backups = backups;
		this.files = files;
		this.backupService = backupService;
		this.properties = properties;
		this.storage = storage;
		this.clock = clock;
		this.hashExecutor = hashExecutor;
		this.transactions = transactions;
		this.metrics = metrics;
		this.globalCapacity = new Semaphore(properties.transfer().maxConcurrentTotal(), true);
	}

	public UploadResult upload(
			UUID transferId,
			UUID deviceId,
			UUID runId,
			long uploadOffset,
			long contentLength,
			InputStream input
	) {
		validateLength(contentLength);
		if (uploadOffset < 0) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_OFFSET",
					"Upload-Offset must be non-negative");
		}

		ReentrantLock lock = transferLocks.computeIfAbsent(transferId, ignored -> new ReentrantLock());
		if (!lock.tryLock()) {
			throw capacity("This transfer already has an active writer");
		}
		Semaphore perDevice = deviceCapacity.computeIfAbsent(deviceId,
				ignored -> new Semaphore(properties.transfer().maxConcurrentPerDevice(), true));
		boolean global = false;
		boolean device = false;
		boolean counted = false;
		try {
			global = globalCapacity.tryAcquire();
			device = perDevice.tryAcquire();
			if (!global || !device) {
				throw capacity("Transfer capacity is currently exhausted");
			}
			metrics.transferStarted();
			counted = true;
			Records.Transfer transfer = ownedTransfer(transferId, deviceId, runId);
			Records.BackupRun run = backupService.ownedRun(runId, deviceId);
			if ("CANCELLED".equals(run.state()) || "COMPLETED".equals(run.state())) {
				throw new DomainException(HttpStatus.CONFLICT, "RUN_NOT_ACTIVE",
						"The backup run no longer accepts upload content");
			}
			if ("COMMITTED".equals(transfer.state())) {
				return new UploadResult(transfer.expectedSize(), true);
			}
			if ("REJECTED".equals(transfer.state()) || "EXPIRED".equals(transfer.state())) {
				throw new DomainException(HttpStatus.CONFLICT, "TRANSFER_NOT_ACTIVE",
						"The transfer no longer accepts upload content");
			}
			if (transfer.acceptedOffset() != uploadOffset) {
				throw new DomainException(HttpStatus.CONFLICT, "UPLOAD_OFFSET_MISMATCH",
						"Upload-Offset does not match the durable server offset",
						java.util.Map.of("uploadOffset", transfer.acceptedOffset()));
			}
			if (contentLength > transfer.expectedSize() - uploadOffset) {
				throw new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "TRANSFER_SIZE_EXCEEDED",
						"Segment would exceed the expected file size");
			}
			ensureSpace(contentLength);
			Path partial = storage.resolveSafe(transfer.partialPath());
			writeSegment(partial, uploadOffset, contentLength, input);

			long accepted = uploadOffset + contentLength;
			Instant now = clock.instant();
			transfers.updateOffset(transferId, accepted,
					accepted == 0 ? "PENDING" : "PARTIAL",
					now, now.plus(properties.storage().partialRetention()));
			metrics.uploaded(contentLength);
			backups.updateRunState(runId, "TRANSFERRING");

			if (accepted == transfer.expectedSize()) {
				verifyOnBoundedExecutor(transfer, partial);
				return new UploadResult(accepted, true);
			}
			return new UploadResult(accepted, false);
		} finally {
			if (counted) {
				metrics.transferFinished();
			}
			if (device) {
				perDevice.release();
			}
			if (global) {
				globalCapacity.release();
			}
			lock.unlock();
			if (!lock.hasQueuedThreads()) {
				transferLocks.remove(transferId, lock);
			}
		}
	}

	public Records.Transfer status(UUID transferId, UUID deviceId, UUID runId) {
		return ownedTransfer(transferId, deviceId, runId);
	}

	private void verifyOnBoundedExecutor(Records.Transfer transfer, Path partial) {
		FutureTask<Void> task = new FutureTask<>(() -> {
			Timer.Sample sample = Timer.start();
			try {
				verifyAndCommit(transfer, partial);
			} finally {
				sample.stop(metrics.hashDuration());
			}
			return null;
		});
		try {
			hashExecutor.execute(task);
			task.get();
		} catch (RejectedExecutionException exception) {
			throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_CAPACITY",
					"Hash verification capacity is exhausted");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "VERIFICATION_INTERRUPTED",
					"Hash verification was interrupted");
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw storageFailure(exception.getCause());
		}
	}

	private void verifyAndCommit(Records.Transfer transfer, Path partial) {
		String actualHash;
		try {
			if (Files.size(partial) != transfer.expectedSize()) {
				throw new DomainException(HttpStatus.CONFLICT, "PARTIAL_SIZE_MISMATCH",
						"Partial file size does not match expected size");
			}
			actualHash = sha256(partial);
		} catch (IOException exception) {
			throw storageFailure(exception);
		}
		if (!actualHash.equals(transfer.expectedSha256())) {
			metrics.hashFailed();
			quarantine(partial, transfer.transferId() + "-checksum.part");
			transfers.markRejected(transfer.transferId(), clock.instant());
			throw new DomainException(HttpStatus.UNPROCESSABLE_CONTENT, "CHECKSUM_MISMATCH",
					"Uploaded content does not match the declared SHA-256");
		}

		Records.ManifestEntry manifest = backups.findManifestEntry(
						transfer.runId(), transfer.clientFileKey())
				.orElseThrow(() -> new IllegalStateException("Manifest entry missing for transfer"));
		Instant now = clock.instant();
		UUID fileId = UUID.randomUUID();
		Instant storageDate = manifest.capturedAt() == null ? now : manifest.capturedAt();
		String safeName = sanitizeName(manifest.displayName());
		String storedPath = Path.of(
				"data", transfer.deviceId().toString(),
				Integer.toString(storageDate.atZone(ZoneOffset.UTC).getYear()),
				String.format("%02d", storageDate.atZone(ZoneOffset.UTC).getMonthValue()),
				fileId + "_" + safeName).toString().replace('\\', '/');
		Records.StoredFile staged = new Records.StoredFile(
				fileId, transfer.deviceId(), transfer.clientFileKey(), manifest.displayName(),
				manifest.relativePath(), manifest.mediaType(), manifest.mimeType(),
				transfer.expectedSize(), transfer.expectedSha256(), manifest.capturedAt(),
				manifest.modifiedAt(), storedPath, now, "QUARANTINED");

		transactions.executeWithoutResult(status -> {
			files.insertStaged(staged);
			transfers.stageFile(transfer.transferId(), fileId);
		});

		Path destination = storage.resolveSafe(storedPath);
		try {
			Files.createDirectories(destination.getParent());
			try {
				Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(partial, destination);
			}
		} catch (IOException exception) {
			throw storageFailure(exception);
		}

		try {
			transactions.executeWithoutResult(status -> {
				files.markCommitted(fileId);
				transfers.markCommitted(transfer.transferId(), clock.instant());
			});
		} catch (DataIntegrityViolationException duplicateIdentity) {
			try {
				Files.deleteIfExists(destination);
			} catch (IOException ignored) {
				// Recovery will report the quarantined row if cleanup cannot finish.
			}
			transactions.executeWithoutResult(status -> {
				files.markDeleted(fileId);
				transfers.markCommitted(transfer.transferId(), clock.instant());
			});
		}
	}

	private void writeSegment(Path partial, long offset, long length, InputStream input) {
		try {
			Files.createDirectories(partial.getParent());
			try (FileChannel channel = FileChannel.open(partial,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
				long actualSize = channel.size();
				if (actualSize != offset) {
					throw new DomainException(HttpStatus.CONFLICT, "PARTIAL_OFFSET_MISMATCH",
							"Partial file does not match the durable server offset",
							java.util.Map.of("uploadOffset", Math.min(actualSize, offset)));
				}
				channel.position(offset);
				byte[] bytes = new byte[64 * 1024];
				long remaining = length;
				try {
					while (remaining > 0) {
						int read = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
						if (read < 0) {
							throw new DomainException(HttpStatus.BAD_REQUEST, "EARLY_EOF",
									"Request body ended before Content-Length bytes were received");
						}
						ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
						while (buffer.hasRemaining()) {
							channel.write(buffer);
						}
						remaining -= read;
					}
					if (input.read() != -1) {
						throw new DomainException(HttpStatus.BAD_REQUEST, "EXCESS_REQUEST_BODY",
								"Request body contains more bytes than Content-Length");
					}
					channel.force(true);
				} catch (RuntimeException | IOException failure) {
					channel.truncate(offset);
					channel.force(true);
					throw failure;
				}
			}
		} catch (DomainException exception) {
			throw exception;
		} catch (IOException exception) {
			throw storageFailure(exception);
		}
	}

	private Records.Transfer ownedTransfer(UUID id, UUID deviceId, UUID runId) {
		Records.Transfer transfer = transfers.find(id)
				.orElseThrow(() -> new DomainException(
						HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer was not found"));
		if (!transfer.deviceId().equals(deviceId) || !transfer.runId().equals(runId)) {
			throw new DomainException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
					"Transfer was not found for this device and backup run");
		}
		return transfer;
	}

	private void validateLength(long contentLength) {
		if (contentLength < 0) {
			throw new DomainException(HttpStatus.LENGTH_REQUIRED, "CONTENT_LENGTH_REQUIRED",
					"Content-Length is required for upload segments");
		}
		if (contentLength > properties.transfer().maxSegmentBytes()) {
			throw new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "SEGMENT_TOO_LARGE",
					"Upload segment exceeds the configured maximum");
		}
	}

	private void ensureSpace(long contentLength) {
		try {
			long required = Math.addExact(properties.storage().minimumFreeBytes(), contentLength);
			if (storage.usableSpace() < required) {
				throw new DomainException(HttpStatus.INSUFFICIENT_STORAGE, "INSUFFICIENT_STORAGE",
						"Storage safety margin would be exceeded");
			}
		} catch (ArithmeticException | IOException exception) {
			throw storageFailure(exception);
		}
	}

	private String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[128 * 1024];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					if (read > 0) {
						digest.update(buffer, 0, read);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private String sanitizeName(String name) {
		String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "_")
				.replaceAll("_+", "_");
		if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
			return "file";
		}
		return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180);
	}

	private void quarantine(Path source, String name) {
		try {
			Path target = storage.resolveSafe(Path.of("quarantine", name).toString());
			Files.createDirectories(target.getParent());
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// The rejected row remains visible to cleanup/recovery.
		}
	}

	private DomainException capacity(String detail) {
		return new DomainException(HttpStatus.TOO_MANY_REQUESTS, "TRANSFER_CAPACITY", detail,
				java.util.Map.of("retryAfterSeconds", 1));
	}

	private DomainException storageFailure(Throwable exception) {
		return new DomainException(HttpStatus.INSUFFICIENT_STORAGE, "STORAGE_IO_ERROR",
				"Storage operation could not be completed");
	}

	public record UploadResult(long offset, boolean complete) {
	}
}
