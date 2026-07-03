package com.hitstudio.syncup.server.support;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ManifestBodyLimitFilter extends OncePerRequestFilter {
	private static final Pattern MANIFEST_PATH =
			Pattern.compile("^/api/v1/backups/[^/]+/manifest$");

	private final long limit;
	private final ObjectMapper objectMapper;

	public ManifestBodyLimitFilter(SyncUpProperties properties, ObjectMapper objectMapper) {
		this.limit = properties.manifest().maxBodyBytes();
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod())
				|| !MANIFEST_PATH.matcher(request.getRequestURI()).matches();
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		if (request.getContentLengthLong() > limit) {
			writeTooLarge(response);
			return;
		}
		filterChain.doFilter(new LimitedRequest(request, limit), response);
	}

	private void writeTooLarge(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), Map.of(
				"type", "urn:syncup:error:manifest_body_too_large",
				"title", "Payload too large",
				"status", HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
				"detail", "Manifest JSON exceeds the configured request-body limit",
				"code", "MANIFEST_BODY_TOO_LARGE"));
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {
		private final long limit;

		private LimitedRequest(HttpServletRequest request, long limit) {
			super(request);
			this.limit = limit;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			return new LimitedInputStream(super.getInputStream(), limit);
		}
	}

	private static final class LimitedInputStream extends ServletInputStream {
		private final ServletInputStream delegate;
		private final long limit;
		private long read;

		private LimitedInputStream(ServletInputStream delegate, long limit) {
			this.delegate = delegate;
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int value = delegate.read();
			if (value >= 0) {
				count(1);
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			int count = delegate.read(bytes, offset, length);
			if (count > 0) {
				count(count);
			}
			return count;
		}

		private void count(int amount) throws PayloadTooLargeException {
			read += amount;
			if (read > limit) {
				throw new PayloadTooLargeException();
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			delegate.setReadListener(readListener);
		}
	}

	static final class PayloadTooLargeException extends IOException {
		private PayloadTooLargeException() {
			super("Manifest request body is too large");
		}
	}
}
