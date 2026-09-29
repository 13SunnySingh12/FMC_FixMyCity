package com.fixmycity;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

/** Same PostgreSQL major version and pgvector extension as the Neon database. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));
	}

}
