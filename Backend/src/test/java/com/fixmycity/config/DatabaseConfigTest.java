package com.fixmycity.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseConfigTest {

	@Test
	void mapsNeonConnectionStringToJdbc() {
		JdbcConnectionDetails details = DatabaseConfig.fromUrl(
				"postgresql://app_user:p%40ss+word@ep-example.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

		assertThat(details.getJdbcUrl()).isEqualTo(
				"jdbc:postgresql://ep-example.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
		assertThat(details.getUsername()).isEqualTo("app_user");
		assertThat(details.getPassword()).isEqualTo("p@ss+word");
	}

	@Test
	void keepsAnExplicitPort() {
		assertThat(DatabaseConfig.fromUrl("postgres://u:p@localhost:5433/fmc").getJdbcUrl())
			.isEqualTo("jdbc:postgresql://localhost:5433/fmc");
	}

	@Test
	void rejectsInvalidValuesWithoutEchoingThem() {
		assertThatThrownBy(() -> DatabaseConfig.fromUrl("<SET_LOCALLY>"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("SET_LOCALLY");
		assertThatThrownBy(() -> DatabaseConfig.fromUrl("mysql://u:hunter2@host/db"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("hunter2");
	}

}
