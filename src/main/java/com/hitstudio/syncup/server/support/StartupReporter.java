package com.hitstudio.syncup.server.support;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import com.hitstudio.syncup.server.discovery.ServerIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;

@Component
public class StartupReporter {
	private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

	private final ServerIdentityService identityService;
	private final SyncUpProperties properties;
	private final StorageBootstrap storage;
	private final int port;

	public StartupReporter(
			ServerIdentityService identityService,
			SyncUpProperties properties,
			StorageBootstrap storage,
			@Value("${server.port}") int port
	) {
		this.identityService = identityService;
		this.properties = properties;
		this.storage = storage;
		this.port = port;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void ready() {
		var identity = identityService.identity();
		log.info(LogJson.event("server_ready",
				"serverId", identity.serverId(),
				"serverName", identity.serverName(),
				"httpPort", port,
				"storageRoot", storage.root(),
				"addresses", addresses()));
		log.warn(LogJson.event("trusted_lan_warning",
				"message", "No authentication or TLS is enabled; do not expose this server outside a trusted private LAN"));
	}

	private java.util.List<String> addresses() {
		ArrayList<String> result = new ArrayList<>();
		try {
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback()) {
					continue;
				}
				for (var address : Collections.list(network.getInetAddresses())) {
					if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
						result.add("http://" + address.getHostAddress() + ":" + port + "/api/v1");
					}
				}
			}
		} catch (Exception ignored) {
			result.add("http://localhost:" + port + "/api/v1");
		}
		return result;
	}
}
