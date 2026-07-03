package com.hitstudio.syncup.server.file;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FileDtos {
	private FileDtos() {
	}

	public record FileItem(
			UUID fileId,
			UUID deviceId,
			String originalName,
			String originalRelativePath,
			String mediaType,
			String mimeType,
			long sizeBytes,
			String sha256,
			Instant capturedAt,
			Instant modifiedAt,
			Instant backedUpAt
	) {
	}

	public record FilePage(List<FileItem> files, String nextCursor) {
	}
}
