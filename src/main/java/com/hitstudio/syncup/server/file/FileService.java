package com.hitstudio.syncup.server.file;

import com.hitstudio.syncup.server.persistence.JdbcFileRepository;
import com.hitstudio.syncup.server.persistence.Records;
import com.hitstudio.syncup.server.support.DomainException;
import com.hitstudio.syncup.server.support.StorageBootstrap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;

@Service
public class FileService {
	private final JdbcFileRepository files;
	private final StorageBootstrap storage;

	public FileService(JdbcFileRepository files, StorageBootstrap storage) {
		this.files = files;
		this.storage = storage;
	}

	public FileDtos.FilePage list(
			UUID deviceId, String mediaType, Instant capturedAfter, String cursor, int limit
	) {
		if (limit < 1 || limit > 500) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE",
					"limit must be between 1 and 500");
		}
		Cursor decoded = decodeCursor(cursor);
		var rows = files.listCommitted(
				deviceId,
				mediaType == null ? null : mediaType.toUpperCase(java.util.Locale.ROOT),
				capturedAfter,
				decoded == null ? null : decoded.backedUpAt(),
				decoded == null ? null : decoded.fileId(),
				limit + 1);
		boolean more = rows.size() > limit;
		if (more) {
			rows = new ArrayList<>(rows.subList(0, limit));
		}
		var items = rows.stream().map(this::item).toList();
		String next = more && !rows.isEmpty() ? encodeCursor(rows.getLast()) : null;
		return new FileDtos.FilePage(items, next);
	}

	public Download open(UUID fileId) {
		Records.StoredFile file = files.find(fileId)
				.orElseThrow(() -> new DomainException(
						HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File was not found"));
		if (!"COMMITTED".equals(file.status())) {
			throw new DomainException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND",
					"Committed file was not found");
		}
		Path path = storage.resolveSafe(file.storedPath());
		try {
			if (!Files.isRegularFile(path) || Files.size(path) != file.sizeBytes()) {
				files.markMissing(fileId);
				throw new DomainException(HttpStatus.GONE, "FILE_CONTENT_MISSING",
						"File content is no longer available");
			}
		} catch (IOException exception) {
			throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_IO_ERROR",
					"File content could not be inspected");
		}
		return new Download(file, path);
	}

	private FileDtos.FileItem item(Records.StoredFile file) {
		return new FileDtos.FileItem(
				file.fileId(), file.deviceId(), file.originalName(),
				file.originalRelativePath(), file.mediaType(), file.mimeType(),
				file.sizeBytes(), file.sha256(), file.capturedAt(),
				file.modifiedAt(), file.backedUpAt());
	}

	private String encodeCursor(Records.StoredFile file) {
		String raw = file.backedUpAt() + "|" + file.fileId();
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor),
					java.nio.charset.StandardCharsets.UTF_8);
			int separator = raw.lastIndexOf('|');
			return new Cursor(
					Instant.parse(raw.substring(0, separator)),
					UUID.fromString(raw.substring(separator + 1)));
		} catch (RuntimeException exception) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
					"File listing cursor is invalid");
		}
	}

	public record Download(Records.StoredFile file, Path path) {
	}

	private record Cursor(Instant backedUpAt, UUID fileId) {
	}
}
