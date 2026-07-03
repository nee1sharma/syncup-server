package com.hitstudio.syncup.server.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SyncUpMetrics {
	private final AtomicInteger activeTransfers = new AtomicInteger();
	private final Counter uploadedBytes;
	private final Counter downloadedBytes;
	private final Counter hashFailures;
	private final Timer hashDuration;

	public SyncUpMetrics(MeterRegistry registry) {
		Gauge.builder("syncup.transfers.active", activeTransfers, AtomicInteger::get)
				.description("Upload requests currently using transfer capacity")
				.register(registry);
		uploadedBytes = Counter.builder("syncup.transfer.uploaded")
				.baseUnit("bytes")
				.description("Durably accepted upload bytes")
				.register(registry);
		downloadedBytes = Counter.builder("syncup.file.downloaded")
				.baseUnit("bytes")
				.description("Restore bytes written to clients")
				.register(registry);
		hashFailures = Counter.builder("syncup.hash.failures")
				.description("SHA-256 verification failures")
				.register(registry);
		hashDuration = Timer.builder("syncup.hash.duration")
				.description("SHA-256 verification duration")
				.register(registry);
	}

	public void transferStarted() {
		activeTransfers.incrementAndGet();
	}

	public void transferFinished() {
		activeTransfers.decrementAndGet();
	}

	public void uploaded(long bytes) {
		uploadedBytes.increment(bytes);
	}

	public void downloaded(long bytes) {
		downloadedBytes.increment(bytes);
	}

	public void hashFailed() {
		hashFailures.increment();
	}

	public Timer hashDuration() {
		return hashDuration;
	}
}
