package com.hitstudio.syncup.server;

import com.hitstudio.syncup.server.config.SyncUpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(SyncUpProperties.class)
public class SyncUpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SyncUpServerApplication.class, args);
	}
}
