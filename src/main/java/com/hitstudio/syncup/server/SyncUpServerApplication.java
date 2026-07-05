package com.hitstudio.syncup.server;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(SyncUpProperties.class)
public class SyncUpServerApplication {

	public static void main(String[] args) {
		bootstrapLogFolder();
		SpringApplication.run(SyncUpServerApplication.class, args);
	}

	private static void bootstrapLogFolder() {
		Path logRoot = Path.of(System.getProperty("syncup.logging.root", "./syncup-data/logs"))
				.toAbsolutePath()
				.normalize();
		try {
			Files.createDirectories(logRoot);
			System.setProperty("syncup.logging.root", logRoot.toString());
		} catch (Exception exception) {
			throw new IllegalStateException("Could not initialize SyncUp log directory", exception);
		}
	}
}
