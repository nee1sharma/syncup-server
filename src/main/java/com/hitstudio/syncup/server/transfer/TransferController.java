package com.hitstudio.syncup.server.transfer;

import com.hitstudio.syncup.server.persistence.Records;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.NotBlank;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
	public static final String DEVICE_HEADER = "X-SyncUp-Device-Id";
	public static final String DEVICE_NAME_HEADER = "X-SyncUp-Device-Name";
	public static final String RUN_HEADER = "X-SyncUp-Run-Id";
	public static final String OFFSET_HEADER = "Upload-Offset";

	private final TransferService service;

	public TransferController(TransferService service) {
		this.service = service;
	}

	@PutMapping("/{transferId}/content")
	ResponseEntity<Void> upload(
			@PathVariable UUID transferId,
			@RequestHeader(DEVICE_HEADER) UUID deviceId,
			@RequestHeader(DEVICE_NAME_HEADER) @NotBlank String deviceName,
			@RequestHeader(RUN_HEADER) UUID runId,
			@RequestHeader(OFFSET_HEADER) long uploadOffset,
			HttpServletRequest request
	) throws IOException {
		var result = service.upload(
				transferId, deviceId, deviceName, runId, uploadOffset,
				request.getContentLengthLong(), request.getInputStream());
		return ResponseEntity.noContent()
				.header(OFFSET_HEADER, Long.toString(result.offset()))
				.header("Upload-Complete", Boolean.toString(result.complete()))
				.build();
	}

	@GetMapping("/{transferId}")
	TransferStatus status(
			@PathVariable UUID transferId,
			@RequestHeader(DEVICE_HEADER) UUID deviceId,
			@RequestHeader(DEVICE_NAME_HEADER) @NotBlank String deviceName,
			@RequestHeader(RUN_HEADER) UUID runId
	) {
		Records.Transfer transfer = service.status(transferId, deviceId, deviceName, runId);
		return new TransferStatus(
				transfer.transferId(), transfer.state(), transfer.acceptedOffset(),
				transfer.expectedSize(), transfer.expiresAt());
	}

	record TransferStatus(
			UUID transferId,
			String state,
			long uploadOffset,
			long expectedSize,
			java.time.Instant expiresAt
	) {
	}
}
