package com.hitstudio.syncup.server.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> domain(DomainException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
		problem.setTitle(title(exception.status()));
		problem.setType(URI.create("urn:syncup:error:" + exception.code().toLowerCase()));
		problem.setProperty("code", exception.code());
		exception.properties().forEach(problem::setProperty);
		ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
		Object uploadOffset = exception.properties().get("uploadOffset");
		if (uploadOffset != null) {
			response.header("Upload-Offset", uploadOffset.toString());
		}
		Object contentRange = exception.properties().get("contentRange");
		if (contentRange != null) {
			response.header("Content-Range", contentRange.toString());
		}
		Object retryAfter = exception.properties().get("retryAfterSeconds");
		if (retryAfter != null) {
			response.header("Retry-After", retryAfter.toString());
		}
		return response.body(problem);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : exception.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		log.warn(LogJson.event("api_request_invalid",
				"method", request.getMethod(),
				"path", request.getRequestURI(),
				"errorCode", "VALIDATION_FAILED",
				"fieldCount", errors.size(),
				"errors", errors));
		return validationProblem(errors);
	}

	@ExceptionHandler({
			ConstraintViolationException.class,
			HandlerMethodValidationException.class,
			MissingRequestHeaderException.class,
			IllegalArgumentException.class
	})
	ResponseEntity<ProblemDetail> malformed(Exception exception, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "The request is malformed or contains invalid values");
		problem.setTitle("Invalid request");
		problem.setType(URI.create("urn:syncup:error:invalid_request"));
		problem.setProperty("code", "INVALID_REQUEST");
		log.warn(LogJson.event("api_request_malformed",
				"method", request.getMethod(),
				"path", request.getRequestURI(),
				"query", request.getQueryString(),
				"errorType", exception.getClass().getSimpleName(),
				"message", exception.getMessage()));
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ManifestBodyLimitFilter.PayloadTooLargeException) {
				ProblemDetail problem = ProblemDetail.forStatusAndDetail(
						HttpStatus.PAYLOAD_TOO_LARGE,
						"Manifest JSON exceeds the configured request-body limit");
				problem.setTitle("Payload too large");
				problem.setType(URI.create("urn:syncup:error:manifest_body_too_large"));
				problem.setProperty("code", "MANIFEST_BODY_TOO_LARGE");
				log.warn(LogJson.event("api_request_too_large",
						"method", request.getMethod(),
						"path", request.getRequestURI(),
						"errorCode", "MANIFEST_BODY_TOO_LARGE",
						"message", cause.getMessage()));
				return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
			}
			cause = cause.getCause();
		}
		return malformed(exception, request);
	}

	private ResponseEntity<ProblemDetail> validationProblem(Map<String, String> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "One or more request fields are invalid");
		problem.setTitle("Validation failed");
		problem.setType(URI.create("urn:syncup:error:validation_failed"));
		problem.setProperty("code", "VALIDATION_FAILED");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	private String title(HttpStatus status) {
		return switch (status) {
			case NOT_FOUND -> "Resource not found";
			case CONFLICT -> "Request conflict";
			case PAYLOAD_TOO_LARGE -> "Payload too large";
			case TOO_MANY_REQUESTS -> "Transfer capacity exhausted";
			case INSUFFICIENT_STORAGE -> "Insufficient storage";
			default -> status.getReasonPhrase();
		};
	}
}
