package com.fixmycity.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets both services share Neon's single libpq-style DATABASE_URL
 * (postgresql://user:password@host/database?params) instead of a separate JDBC URL.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

	@Bean
	@ConditionalOnProperty("DATABASE_URL")
	JdbcConnectionDetails databaseUrlConnectionDetails(@Value("${DATABASE_URL}") String databaseUrl) {
		return fromUrl(databaseUrl);
	}

	static JdbcConnectionDetails fromUrl(String databaseUrl) {
		URI uri;
		try {
			uri = new URI(databaseUrl.strip());
		}
		catch (URISyntaxException ex) {
			// Never include the URL itself: it contains the password.
			throw new IllegalStateException("DATABASE_URL is not a valid URL");
		}
		boolean postgresScheme = "postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme());
		if (!postgresScheme || uri.getHost() == null || uri.getRawUserInfo() == null || uri.getRawPath().length() < 2) {
			throw new IllegalStateException("DATABASE_URL must look like postgresql://user:password@host/database");
		}
		String[] credentials = uri.getRawUserInfo().split(":", 2);
		String username = decode(credentials[0]);
		String password = credentials.length == 2 ? decode(credentials[1]) : null;
		String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
				+ uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
		return new JdbcConnectionDetails() {

			@Override
			public String getUsername() {
				return username;
			}

			@Override
			public String getPassword() {
				return password;
			}

			@Override
			public String getJdbcUrl() {
				return jdbcUrl;
			}

		};
	}

	// Percent-decoding only: a literal '+' in a password must stay '+'.
	private static String decode(String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

}
