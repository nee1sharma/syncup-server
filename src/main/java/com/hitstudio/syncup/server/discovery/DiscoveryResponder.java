package com.hitstudio.syncup.server.discovery;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

@Component
public class DiscoveryResponder {
	private static final Logger log = LoggerFactory.getLogger(DiscoveryResponder.class);
	private static final long MIN_RESPONSE_INTERVAL_MILLIS = 250;

	private final SyncUpProperties properties;
	private final ServerIdentityService identityService;
	private final ObjectMapper objectMapper;
	private final int httpPort;
	private final AtomicBoolean running = new AtomicBoolean();
	private final ConcurrentHashMap<String, Long> lastResponses = new ConcurrentHashMap<>();
	private DatagramSocket socket;
	private ExecutorService executor;

	public DiscoveryResponder(
			SyncUpProperties properties,
			ServerIdentityService identityService,
			ObjectMapper objectMapper,
			@Value("${server.port}") int httpPort
	) {
		this.properties = properties;
		this.identityService = identityService;
		this.objectMapper = objectMapper;
		this.httpPort = httpPort;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (!properties.discovery().enabled() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			socket = new DatagramSocket(null);
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress(properties.discovery().port()));
			socket.setSoTimeout(1_000);
			executor = Executors.newSingleThreadExecutor(
					Thread.ofPlatform().name("syncup-discovery-", 0).daemon(true).factory());
			executor.execute(this::receiveLoop);
			log.info("event=discovery_started udpPort={}", properties.discovery().port());
		} catch (IOException exception) {
			running.set(false);
			throw new IllegalStateException("Could not bind SyncUp discovery port", exception);
		}
	}

	private void receiveLoop() {
		byte[] buffer = new byte[properties.discovery().maxDatagramBytes()];
		while (running.get()) {
			try {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				socket.receive(packet);
				handle(packet);
			} catch (SocketTimeoutException ignored) {
				// Allows prompt lifecycle checks.
			} catch (SocketException exception) {
				if (running.get()) {
					log.warn("event=discovery_socket_error", exception);
				}
			} catch (Exception exception) {
				log.debug("event=discovery_request_rejected reason=malformed");
			}
		}
	}

	private void handle(DatagramPacket packet) throws IOException {
		if (packet.getLength() == properties.discovery().maxDatagramBytes()) {
			return;
		}
		DiscoveryRequest request = objectMapper.readValue(
				packet.getData(), packet.getOffset(), packet.getLength(), DiscoveryRequest.class);
		if (!"SYNCUP_DISCOVER".equals(request.type())
				|| !"v1".equals(request.apiVersion())
				|| request.requestId() == null) {
			return;
		}
		String source = packet.getAddress().getHostAddress();
		long now = System.currentTimeMillis();
		Long previous = lastResponses.put(source, now);
		if (previous != null && now - previous < MIN_RESPONSE_INTERVAL_MILLIS) {
			return;
		}
		if (lastResponses.size() > 1_024) {
			lastResponses.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
		}

		InetAddress localAddress = reachableLocalAddress(packet.getAddress());
		var identity = identityService.identity();
		DiscoveryResponse response = new DiscoveryResponse(
				"SYNCUP_SERVER", request.requestId(), identity.serverId(),
				identity.serverName(), "v1", httpPort,
				"http://" + hostLiteral(localAddress) + ":" + httpPort + "/api/v1",
				List.of("INCREMENTAL_BACKUP", "RESUMABLE_UPLOAD", "RANGE_DOWNLOAD"));
		byte[] json = objectMapper.writeValueAsBytes(response);
		DatagramPacket reply = new DatagramPacket(
				json, json.length, packet.getAddress(), packet.getPort());
		socket.send(reply);
	}

	private InetAddress reachableLocalAddress(InetAddress remote) {
		try (DatagramSocket route = new DatagramSocket()) {
			route.connect(remote, 9);
			return route.getLocalAddress();
		} catch (SocketException exception) {
			return InetAddress.getLoopbackAddress();
		}
	}

	private String hostLiteral(InetAddress address) {
		String host = address.getHostAddress();
		int scope = host.indexOf('%');
		if (scope >= 0) {
			host = host.substring(0, scope);
		}
		return host.contains(":") ? "[" + host + "]" : host;
	}

	@PreDestroy
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (socket != null) {
			socket.close();
		}
		if (executor != null) {
			executor.shutdownNow();
		}
		log.info("event=discovery_stopped");
	}

	public record DiscoveryRequest(String type, String apiVersion, UUID requestId) {
	}

	public record DiscoveryResponse(
			String type,
			UUID requestId,
			UUID serverId,
			String serverName,
			String apiVersion,
			int httpPort,
			String baseUrl,
			List<String> capabilities
	) {
	}
}
