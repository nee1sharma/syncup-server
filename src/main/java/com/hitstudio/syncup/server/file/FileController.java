package com.hitstudio.syncup.server.file;

import com.hitstudio.syncup.server.support.DomainException;
import com.hitstudio.syncup.server.support.SyncUpMetrics;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
	private final FileService service;
	private final SyncUpMetrics metrics;

	public FileController(FileService service, SyncUpMetrics metrics) {
		this.service = service;
		this.metrics = metrics;
	}

	@GetMapping
	FileDtos.FilePage list(
			@RequestParam(required = false) UUID deviceId,
			@RequestParam(required = false) String mediaType,
			@RequestParam(required = false) Instant capturedAfter,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "100") int limit
	) {
		return service.list(deviceId, mediaType, capturedAfter, cursor, limit);
	}

	@GetMapping("/{fileId}/content")
	ResponseEntity<StreamingResponseBody> content(
			@PathVariable UUID fileId,
			@RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader
	) {
		FileService.Download download = service.open(fileId);
		long size = download.file().sizeBytes();
		ByteRange range = parseRange(rangeHeader, size);
		long start = range == null ? 0 : range.start();
		long end = range == null ? size - 1 : range.end();
		long length = size == 0 ? 0 : end - start + 1;

		StreamingResponseBody body = output -> stream(download.path(), output, start, length);
		ResponseEntity.BodyBuilder response = range == null
				? ResponseEntity.ok()
				: ResponseEntity.status(HttpStatus.PARTIAL_CONTENT);
		response.header(HttpHeaders.ACCEPT_RANGES, "bytes")
				.header(HttpHeaders.ETAG, "\"" + download.file().sha256() + "\"")
				.header(HttpHeaders.CONTENT_LENGTH, Long.toString(length))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment()
								.filename(download.file().originalName())
								.build().toString())
				.contentType(safeMediaType(download.file().mimeType()));
		if (range != null) {
			response.header(HttpHeaders.CONTENT_RANGE,
					"bytes " + start + "-" + end + "/" + size);
		}
		return response.body(body);
	}

	private void stream(java.nio.file.Path path, OutputStream output, long start, long length)
			throws java.io.IOException {
		try (InputStream input = Files.newInputStream(path)) {
			input.skipNBytes(start);
			byte[] buffer = new byte[128 * 1024];
			long remaining = length;
			while (remaining > 0) {
				int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
				if (read < 0) {
					throw new java.io.EOFException("Committed file ended unexpectedly");
				}
				output.write(buffer, 0, read);
				metrics.downloaded(read);
				remaining -= read;
			}
		}
	}

	private ByteRange parseRange(String header, long size) {
		if (header == null || header.isBlank()) {
			return null;
		}
		if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || size == 0) {
			throw unsatisfiable(size);
		}
		String value = header.substring("bytes=".length()).trim();
		int dash = value.indexOf('-');
		if (dash < 0 || value.indexOf('-', dash + 1) >= 0) {
			throw unsatisfiable(size);
		}
		try {
			if (dash == 0) {
				long suffix = Long.parseLong(value.substring(1));
				if (suffix <= 0) {
					throw unsatisfiable(size);
				}
				long start = Math.max(0, size - suffix);
				return new ByteRange(start, size - 1);
			}
			long start = Long.parseLong(value.substring(0, dash));
			long end = dash == value.length() - 1
					? size - 1
					: Long.parseLong(value.substring(dash + 1));
			if (start < 0 || start >= size || end < start) {
				throw unsatisfiable(size);
			}
			return new ByteRange(start, Math.min(end, size - 1));
		} catch (NumberFormatException exception) {
			throw unsatisfiable(size);
		}
	}

	private DomainException unsatisfiable(long size) {
		return new DomainException(
				HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
				"RANGE_NOT_SATISFIABLE",
				"Requested byte range cannot be satisfied",
				Map.of("contentRange", "bytes */" + size));
	}

	private MediaType safeMediaType(String value) {
		try {
			return MediaType.parseMediaType(value);
		} catch (IllegalArgumentException ignored) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	private record ByteRange(long start, long end) {
	}
}
