package com.fixmycity.auth;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.fixmycity.common.ApiException;
import com.fixmycity.common.Text;
import com.fixmycity.user.Role;
import com.fixmycity.user.User;
import com.fixmycity.user.UserRepository;
import com.fixmycity.user.UserResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	/** Compared against when the email is unknown, so both failure paths cost one bcrypt check. */
	private final String unknownUserHash;

	AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.unknownUserHash = passwordEncoder.encode("unknown-user-timing-guard");
	}

	@Transactional
	public UserResponse register(String name, String email, String password, String phone) {
		String normalizedEmail = User.normalizeEmail(email);
		if (this.users.existsByEmail(normalizedEmail)) {
			throw ApiException.conflict("An account with this email already exists.");
		}
		User user = new User(name.strip(), normalizedEmail, hashPassword(password), Role.CITIZEN);
		user.setPhone(Text.blankToNull(phone));
		return UserResponse.from(this.users.save(user));
	}

	@Transactional(readOnly = true)
	public UserResponse login(String email, String password) {
		Optional<User> user = this.users.findByEmail(User.normalizeEmail(email));
		boolean matches = this.passwordEncoder.matches(password,
				user.map(User::getPasswordHash).orElse(this.unknownUserHash));
		if (user.isEmpty() || !matches) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
		}
		if (!user.get().isActive()) {
			throw ApiException.forbidden("This account has been deactivated.");
		}
		return UserResponse.from(user.get());
	}

	@Transactional(readOnly = true)
	public UserResponse profile(Long userId) {
		return this.users.findById(userId).map(UserResponse::from).orElseThrow(() -> ApiException.notFound("User"));
	}

	/** BCrypt only uses the first 72 bytes; longer passwords are rejected rather than silently truncated. */
	public String hashPassword(String password) {
		if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
			throw ApiException.badRequest("Password must be at most 72 bytes.");
		}
		return this.passwordEncoder.encode(password);
	}

}
