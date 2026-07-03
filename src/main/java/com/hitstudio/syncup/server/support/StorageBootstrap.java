package com.hitstudio.syncup.server.support;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class StorageBootstrap {
	private final SyncUpProperties properties;
	private Path root;
	private FileChannel lockChannel;
	private FileLock processLock;

	public StorageBootstrap(SyncUpProperties properties) {
		this.properties = properties;
	}

	@PostConstruct
	void initialize() throws IOException {
		root = properties.storage().root().toAbsolutePath().normalize();
		Files.createDirectories(root);
		Files.createDirectories(root.resolve("data"));
		Files.createDirectories(root.resolve("partial"));
		Files.createDirectories(root.resolve("quarantine"));
		if (!Files.isDirectory(root) || !Files.isWritable(root)) {
			throw new IllegalStateException("SyncUp storage root is not a writable directory");
		}
		long usable = Files.getFileStore(root).getUsableSpace();
		if (usable < properties.storage().minimumFreeBytes()) {
			throw new IllegalStateException("SyncUp storage does not meet minimum free-space requirement");
		}
		lockChannel = FileChannel.open(root.resolve("syncup.lock"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		processLock = lockChannel.tryLock();
		if (processLock == null) {
			throw new IllegalStateException("Another SyncUp server is using this storage root");
		}
	}

	public Path root() {
		return root;
	}

	public Path resolveSafe(String relativePath) {
		Path resolved = root.resolve(relativePath).normalize();
		if (!resolved.startsWith(root)) {
			throw new IllegalArgumentException("Path escapes storage root");
		}
		return resolved;
	}

	public long usableSpace() throws IOException {
		return Files.getFileStore(root).getUsableSpace();
	}

	@PreDestroy
	void close() throws IOException {
		if (processLock != null && processLock.isValid()) {
			processLock.release();
		}
		if (lockChannel != null) {
			lockChannel.close();
		}
	}
}
