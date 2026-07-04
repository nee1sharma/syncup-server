package com.hitstudio.syncup.server.backup;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/backups")
public class BackupController {
	private final BackupService service;

	public BackupController(BackupService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<BackupDtos.BackupRunResponse> create(
			@Valid @RequestBody BackupDtos.CreateBackupRequest request
	) {
		var response = service.create(request);
		return ResponseEntity.created(URI.create("/api/v1/backups/" + response.runId()))
				.body(response);
	}

	@PostMapping("/{runId}/manifest")
	BackupDtos.ManifestPlanResponse manifest(
			@PathVariable UUID runId,
			@Valid @RequestBody BackupDtos.ManifestBatchRequest request
	) {
		return service.submitManifest(runId, request);
	}

	@PostMapping("/{runId}/complete")
	BackupDtos.BackupRunResponse complete(
			@PathVariable UUID runId,
			@Valid @RequestBody BackupDtos.RunActionRequest request
	) {
		return service.complete(runId, request.deviceId(), request.deviceName());
	}

	@PostMapping("/{runId}/cancel")
	BackupDtos.BackupRunResponse cancel(
			@PathVariable UUID runId,
			@Valid @RequestBody BackupDtos.RunActionRequest request
	) {
		return service.cancel(runId, request.deviceId(), request.deviceName());
	}
}
