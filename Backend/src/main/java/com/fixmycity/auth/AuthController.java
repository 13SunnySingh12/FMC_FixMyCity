package com.fixmycity.auth;

import java.time.Duration;

import com.fixmycity.common.ApiException;
import com.fixmycity.common.RateLimit;
import com.fixmycity.user.User;
import com.fixmycity.user.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	record RegisterRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
			@Pattern(regexp = "^$|^[0-9+()\\- ]{7,20}$", message = "must be a valid phone number") String phone) {
	}

	record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	private final AuthService auth;

	private final TokenService tokens;

	// Slows password guessing against one account: ten wrong passwords lock sign-in for up to ten minutes.
	private final RateLimit failedLogins = new RateLimit(10, Duration.ofMinutes(10),
			"Too many failed sign-in attempts. Please wait a few minutes and try again.");

	AuthController(AuthService auth, TokenService tokens) {
		this.auth = auth;
		this.tokens = tokens;
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		UserResponse user = this.auth.register(request.name(), request.email(), request.password(), request.phone());
		return ResponseEntity.status(HttpStatus.CREATED)
			.header(HttpHeaders.SET_COOKIE, this.tokens.loginCookie(user.id()).toString())
			.body(user);
	}

	@PostMapping("/login")
	ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
		String email = User.normalizeEmail(request.email());
		this.failedLogins.requireCapacity(email);
		UserResponse user;
		try {
			user = this.auth.login(request.email(), request.password());
		}
		catch (ApiException ex) {
			if (ex.getStatus() == HttpStatus.UNAUTHORIZED) {
				this.failedLogins.record(email);
			}
			throw ex;
		}
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, this.tokens.loginCookie(user.id()).toString()).body(user);
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, this.tokens.logoutCookie().toString()).build();
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal AuthUser user) {
		return this.auth.profile(user.id());
	}

}
