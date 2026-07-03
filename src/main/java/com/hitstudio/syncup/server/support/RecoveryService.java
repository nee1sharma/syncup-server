package com.hitstudio.syncup.server.support;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.persistence.JdbcBackupRepository;
import com.hitstudio.syncup.server.persistence.JdbcFileRepository;
import com.hitstudio.syncup.server.persistence.JdbcTransferRepository;
import com.hitstudio.syncup.server.persistence.Records;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecoveryService implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

	private final JdbcBackupRepository backups;
	private final JdbcTransferRepository transfers;
	private final JdbcFileRepository files;
	private final StorageBootstrap storage;
	private final SyncUpProperties properties;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public RecoveryService(
			JdbcBackupRepository backups,
			JdbcTransferRepository transfers,
			JdbcFileRepository files,
			StorageBootstrap storage,
			SyncUpProperties properties,
			TransactionTemplate transactions,
			Clock clock
	) {
		this.backups = backups;
		this.transfers = transfers;
		this.files = files;
		this.storage = storage;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		backups.interruptActiveRuns();
		for (Records.Transfer transfer : transfers.findRecoverable()) {
			try {
				reconcile(transfer);
			} catch (Exception exception) {
				log.warn("event=transfer_recovery_failed transferId={}",
						transfer.transferId(), exception);
			}
		}
		for (Records.StoredFile file : files.findByStatus("COMMITTED")) {
			Path path = storage.resolveSafe(file.storedPath());
			try {
				if (!Files.isRegularFile(path) || Files.size(path) != file.sizeBytes()) {
					files.markMissing(file.fileId());
					log.warn("event=committed_file_missing fileId={}", file.fileId());
				}
			} catch (IOException exception) {
				files.markMissing(file.fileId());
				log.warn("event=committed_file_unreadable fileId={}", file.fileId());
			}
		}
		cleanupOrphanPartials();
	}

	private void reconcile(Records.Transfer transfer) throws IOException {
		Path partial = storage.resolveSafe(transfer.partialPath());
		if ("VERIFYING".equals(transfer.state()) && transfer.stagedFileId() != null) {
			Records.StoredFile staged = files.find(transfer.stagedFileId()).orElse(null);
			if (staged != null) {
				Path finalPath = storage.resolveSafe(staged.storedPath());
				Path source = Files.isRegularFile(finalPath) ? finalPath : partial;
				if (Files.isRegularFile(source)
						&& Files.size(source) == transfer.expectedSize()
						&& sha256(source).equals(transfer.expectedSha256())) {
					if (source.equals(partial)) {
						Files.createDirectories(finalPath.getParent());
						Files.move(partial, finalPath);
					}
					transactions.executeWithoutResult(status -> {
						files.markCommitted(staged.fileId());
						transfers.markCommitted(transfer.transferId(), clock.instant());
					});
					log.info("event=transfer_recovered transferId={}", transfer.transferId());
					return;
				}
				files.markDeleted(staged.fileId());
			}
			long safeOffset = safeOffset(partial, transfer);
			Instant now = clock.instant();
			transfers.resetStaging(
					transfer.transferId(), safeOffset, now,
					now.plus(properties.storage().partialRetention()));
			return;
		}

		long safeOffset = safeOffset(partial, transfer);
		if (safeOffset != transfer.acceptedOffset()) {
			Instant now = clock.instant();
			transfers.updateOffset(
					transfer.transferId(), safeOffset,
					safeOffset == 0 ? "PENDING" : "PARTIAL",
					now, now.plus(properties.storage().partialRetention()));
		}
	}

	private long safeOffset(Path partial, Records.Transfer transfer) throws IOException {
		if (!Files.exists(partial)) {
			return 0;
		}
		long physical = Math.min(Files.size(partial), transfer.expectedSize());
		long safe = Math.min(physical, transfer.acceptedOffset());
		if (Files.size(partial) != safe) {
			try (var channel = java.nio.channels.FileChannel.open(
					partial, StandardOpenOption.WRITE)) {
				channel.truncate(safe);
				channel.force(true);
			}
		}
		return safe;
	}

	@Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
	public void cleanupExpiredTransfers() {
		for (Records.Transfer transfer : transfers.findExpired(clock.instant())) {
			Path partial = storage.resolveSafe(transfer.partialPath());
			try {
				Files.deleteIfExists(partial);
				transfers.expire(transfer.transferId());
				log.info("event=partial_expired transferId={}", transfer.transferId());
			} catch (IOException exception) {
				log.warn("event=partial_cleanup_failed transferId={}", transfer.transferId());
			}
		}
		cleanupOrphanPartials();
	}

	private void cleanupOrphanPartials() {
		Path partialRoot = storage.root().resolve("partial");
		HashSet<Path> live = new HashSet<>();
		for (String relative : transfers.allPartialPaths()) {
			live.add(storage.resolveSafe(relative));
		}
		Instant cutoff = clock.instant().minus(properties.storage().partialRetention());
		try (var paths = Files.walk(partialRoot)) {
			paths.filter(Files::isRegularFile)
					.filter(path -> !live.contains(path))
					.forEach(path -> deleteIfOlderThan(path, cutoff));
		} catch (IOException exception) {
			log.warn("event=orphan_partial_scan_failed");
		}
	}

	private void deleteIfOlderThan(Path path, Instant cutoff) {
		try {
			FileTime modified = Files.getLastModifiedTime(path);
			if (modified.toInstant().isBefore(cutoff)) {
				Files.deleteIfExists(path);
			}
		} catch (IOException exception) {
			log.debug("event=orphan_partial_delete_failed");
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
			throw new IllegalStateException(impossible);
		}
	}

	@PreDestroy
	void interruptRunsOnShutdown() {
		backups.interruptActiveRuns();
	}
}
