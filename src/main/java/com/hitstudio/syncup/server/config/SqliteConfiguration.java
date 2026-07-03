package com.hitstudio.syncup.server.config;

import com.hitstudio.syncup.server.support.StorageBootstrap;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
public class SqliteConfiguration {

	@Bean
	@DependsOn("storageBootstrap")
	DataSource dataSource(@Value("${spring.datasource.url}") String url) throws SQLException {
		HikariDataSource dataSource = DataSourceBuilder.create()
				.type(HikariDataSource.class)
				.driverClassName("org.sqlite.JDBC")
				.url(url)
				.build();
		dataSource.setMaximumPoolSize(4);
		dataSource.setConnectionTimeout(10_000);
		dataSource.setConnectionInitSql("PRAGMA foreign_keys=ON");
		dataSource.addDataSourceProperty("busy_timeout", "5000");
		try (var connection = dataSource.getConnection();
			 var statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA busy_timeout=5000");
			statement.execute("PRAGMA foreign_keys=ON");
		}
		return dataSource;
	}
}
