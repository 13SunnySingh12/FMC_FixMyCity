package com.fixmycity.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fixmycity.IntegrationTest;
import com.fixmycity.user.User;
import com.fixmycity.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuthIntegrationTest {

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void registrationCreatesCitizenAndSetsHardenedCookie() throws Exception {
		String email = uniqueEmail();
		register(email.toUpperCase())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.role").value("CITIZEN"))
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.passwordHash").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("fmc_token="),
					containsString("HttpOnly"), containsString("Secure"), containsString("SameSite=Strict"))));
	}

	@Test
	void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
		String email = uniqueEmail();
		register(email).andExpect(status().isCreated());
		register(email.toUpperCase()).andExpect(status().isConflict());
	}

	@Test
	void invalidRegistrationReportsEachField() throws Exception {
		this.mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Asha", "email": "not-an-email", "password": "short"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.email").exists())
			.andExpect(jsonPath("$.errors.password").exists());
	}

	@Test
	void loginFailuresDoNotRevealWhetherTheEmailExists() throws Exception {
		String email = uniqueEmail();
		register(email);
		login(email, "wrong password").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Invalid email or password."));
		login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Invalid email or password."));
	}

	@Test
	void repeatedWrongPasswordsTemporarilyBlockThatAccountOnly() throws Exception {
		String email = emailRegistered(uniqueEmail());
		for (int i = 0; i < 10; i++) {
			login(email, "wrong password").andExpect(status().isUnauthorized());
		}
		login(email, PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.detail").value("Too many failed sign-in attempts. Please wait a few minutes and try again."));
		login(emailRegistered(uniqueEmail()), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void tokenFromCookieOrHeaderIdentifiesTheUser() throws Exception {
		String email = uniqueEmail();
		String token = tokenOf(login(emailRegistered(email), PASSWORD).andExpect(status().isOk()));

		this.mvc.perform(get("/api/auth/me").cookie(new Cookie("fmc_token", token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email));
		this.mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
	}

	@Test
	void missingForgedOrExpiredTokensAreRejected() throws Exception {
		Long id = this.users.findByEmail(emailRegistered(uniqueEmail())).orElseThrow().getId();
		Instant past = Instant.now().minus(Duration.ofHours(2));
		String expired = this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().subject(id.toString()).issuedAt(past).expiresAt(past.plus(Duration.ofHours(1))).build()))
			.getTokenValue();

		this.mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/auth/me").cookie(new Cookie("fmc_token", "not.a.jwt"))).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/auth/me").cookie(new Cookie("fmc_token", expired))).andExpect(status().isUnauthorized());
	}

	@Test
	void staleCookieDoesNotBlockLoggingInAgain() throws Exception {
		String email = emailRegistered(uniqueEmail());
		this.mvc.perform(post("/api/auth/login").cookie(new Cookie("fmc_token", "stale.or.expired"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(credentials(email, PASSWORD))).andExpect(status().isOk());
	}

	@Test
	void deactivationRevokesAccessImmediately() throws Exception {
		String email = uniqueEmail();
		String token = tokenOf(register(email));
		User user = this.users.findByEmail(email).orElseThrow();
		user.setActive(false);
		this.users.save(user);

		this.mvc.perform(get("/api/auth/me").cookie(new Cookie("fmc_token", token))).andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isForbidden());
	}

	@Test
	void bootstrapAdminCanLogInAndAdminRoutesRequireTheAdminRole() throws Exception {
		String adminToken = tokenOf(login("admin@test.local", "test-only-admin-password")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN")));
		String citizenToken = tokenOf(register(uniqueEmail()));

		this.mvc.perform(get("/api/admin/does-not-exist").cookie(new Cookie("fmc_token", citizenToken)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/admin/does-not-exist").cookie(new Cookie("fmc_token", adminToken)))
			.andExpect(status().isNotFound());
	}

	@Test
	void logoutExpiresTheCookie() throws Exception {
		this.mvc.perform(post("/api/auth/logout"))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(containsString("fmc_token="), containsString("Max-Age=0"))));
	}

	private ResultActions register(String email) throws Exception {
		return this.mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Test Citizen", "email": "%s", "password": "%s"}
				""".formatted(email, PASSWORD)));
	}

	private String emailRegistered(String email) throws Exception {
		register(email).andExpect(status().isCreated());
		return email;
	}

	private ResultActions login(String email, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)));
	}

	private static String credentials(String email, String password) {
		return """
				{"email": "%s", "password": "%s"}
				""".formatted(email, password);
	}

	private static String tokenOf(ResultActions result) {
		return result.andReturn().getResponse().getCookie("fmc_token").getValue();
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

}
