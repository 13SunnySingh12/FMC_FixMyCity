package com.fixmycity.auth;

import com.fixmycity.user.Role;
import com.fixmycity.user.User;
import com.fixmycity.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first admin from ADMIN_EMAIL / ADMIN_PASSWORD; registration only ever creates citizens. */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserRepository users;

	private final AuthService auth;

	private final String email;

	private final String password;

	AdminBootstrap(UserRepository users, AuthService auth, @Value("${fmc.admin.email}") String email,
			@Value("${fmc.admin.password}") String password) {
		this.users = users;
		this.auth = auth;
		this.email = email;
		this.password = password;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (this.users.existsByRole(Role.ADMIN)) {
			return;
		}
		if (this.email.isBlank() || this.password.length() < 12) {
			log.warn("No admin account exists; set ADMIN_EMAIL and a 12+ character ADMIN_PASSWORD to create one");
			return;
		}
		if (this.users.existsByEmail(User.normalizeEmail(this.email))) {
			log.warn("ADMIN_EMAIL belongs to an existing non-admin account; bootstrap admin not created");
			return;
		}
		this.users.save(new User("Administrator", this.email, this.auth.hashPassword(this.password), Role.ADMIN));
		log.info("Bootstrap admin account created");
	}

}
