package com.fixmycity.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.fixmycity.user.User;
import com.fixmycity.user.UserRepository;
import jakarta.servlet.http.Cookie;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.WebUtils;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

	static final String TOKEN_COOKIE = "fmc_token";

	/** These must keep working when the browser still holds an expired or revoked cookie. */
	private static final Set<String> PUBLIC_AUTH_PATHS = Set.of("/api/auth/register", "/api/auth/login",
			"/api/auth/logout");

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, UserRepository users) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable) // stateless API; the token cookie is SameSite=Strict
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests((auth) -> auth
				.requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS.toArray(String[]::new)).permitAll()
				.requestMatchers("/actuator/health/**").permitAll()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer((oauth) -> oauth.bearerTokenResolver(tokenResolver())
				.jwt((jwt) -> jwt.jwtAuthenticationConverter(currentUser(users))));
		return http.build();
	}

	@Bean
	SecretKey jwtKey(@Value("${fmc.jwt.secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).build();
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return NimbusJwtEncoder.withSecretKey(jwtKey).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static BearerTokenResolver tokenResolver() {
		DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
		return (request) -> {
			if (PUBLIC_AUTH_PATHS.contains(request.getRequestURI())) {
				return null;
			}
			String token = header.resolve(request);
			if (token != null) {
				return token;
			}
			Cookie cookie = WebUtils.getCookie(request, TOKEN_COOKIE);
			return (cookie == null || cookie.getValue().isBlank()) ? null : cookie.getValue();
		};
	}

	/** Roles and account status come from the database, so deactivation takes effect immediately. */
	private static Converter<Jwt, AbstractAuthenticationToken> currentUser(UserRepository users) {
		return (jwt) -> {
			User user = users.findById(Long.valueOf(jwt.getSubject()))
				.filter(User::isActive)
				.orElseThrow(() -> new InvalidBearerTokenException("Account is not active"));
			AuthUser principal = new AuthUser(user.getId(), user.getRole(),
					user.getDepartment() == null ? null : user.getDepartment().getId());
			return new UsernamePasswordAuthenticationToken(principal, jwt,
					List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
		};
	}

}
