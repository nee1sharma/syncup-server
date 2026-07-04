package com.hitstudio.syncup.server.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hitstudio.syncup.server.support.LogJson;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/server")
public class ServerController {
	private static final Logger log = LoggerFactory.getLogger(ServerController.class);
	private final ServerIdentityService identityService;

	public ServerController(ServerIdentityService identityService) {
		this.identityService = identityService;
	}

	@GetMapping
	ServerResponse server() {
		var identity = identityService.identity();
		log.info(LogJson.event("server_identity_requested",
				"serverId", identity.serverId(),
				"serverName", identity.serverName()));
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
