package com.hitstudio.syncup.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class TransferExecutorConfiguration {

	@Bean("hashExecutor")
	Executor hashExecutor(SyncUpProperties properties) {
		int workers = Math.max(1, Math.min(2, properties.transfer().maxConcurrentTotal()));
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setThreadNamePrefix("syncup-hash-");
		executor.setCorePoolSize(workers);
		executor.setMaxPoolSize(workers);
		executor.setQueueCapacity(properties.transfer().maxConcurrentTotal());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}
}
