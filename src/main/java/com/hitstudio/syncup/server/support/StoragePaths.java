package com.hitstudio.syncup.server.support;

import org.springframework.http.HttpStatus;

import java.nio.file.Path;

public final class StoragePaths {
	private StoragePaths() {
	}

	public static String deviceFolder(String deviceName) {
		String trimmed = deviceName.trim();
		if (trimmed.isBlank() || hasControl(trimmed) || trimmed.contains("/")
				|| trimmed.contains("\\") || ".".equals(trimmed) || "..".equals(trimmed)) {
			throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_NAME",
					"deviceName must be a plain folder name without path separators, control characters, or dot segments");
		}
		return trimmed;
	}

	public static String committedPath(String deviceName, String originalName) {
		return Path.of("data", deviceFolder(deviceName), originalName).toString().replace('\\', '/');
	}

	private static boolean hasControl(String value) {
		return value.codePoints().anyMatch(Character::isISOControl);
	}
}
