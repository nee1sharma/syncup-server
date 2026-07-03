package com.hitstudio.syncup.server.support;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class DomainException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	private final Map<String, Object> properties;

	public DomainException(HttpStatus status, String code, String message) {
		this(status, code, message, Map.of());
	}

	public DomainException(HttpStatus status, String code, String message, Map<String, Object> properties) {
		super(message);
		this.status = status;
		this.code = code;
		this.properties = Map.copyOf(properties);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Map<String, Object> properties() {
		return properties;
	}
}
