package com.hitstudio.syncup.server.discovery;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/server")
public class ServerController {
	private final ServerIdentityService identityService;

	public ServerController(ServerIdentityService identityService) {
		this.identityService = identityService;
	}

	@GetMapping
	ServerResponse server() {
		var identity = identityService.identity();
		return new ServerResponse(
				identity.serverId(), identity.serverName(), "v1",
				List.of("INCREMENTAL_BACKUP", "RESUMABLE_UPLOAD", "RANGE_DOWNLOAD"));
	}

	public record ServerResponse(
			UUID serverId,
			String serverName,
			String apiVersion,
			List<String> capabilities
	) {
	}
}
