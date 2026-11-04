package com.fraudguard.security;

import com.fraudguard.entity.AppUser;
import com.fraudguard.entity.Role;
import com.fraudguard.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates the first users from environment variables, but ONLY when the users table is empty.
// After that the database is the source of truth: changing the env vars later changes nothing.
@Component
@Slf4j
@RequiredArgsConstructor
public class UserSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SecurityProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        int created = 0;
        created += create(properties.getAdminUsername(), properties.getAdminPassword(), Role.ADMIN);
        created += create(properties.getAnalystUsername(), properties.getAnalystPassword(), Role.ANALYST);
        created += create(properties.getSeedUsername(), properties.getSeedPassword(), Role.ANALYST);
        if (created == 0) {
            log.warn("No users exist and no FRAUDGUARD_*_USERNAME/PASSWORD variables are set: nobody can log in. "
                    + "See .env.example.");
        } else {
            log.info("created {} initial user(s) from environment variables", created);
        }
    }

    private int create(String username, String password, Role role) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return 0;
        }
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setUsername(username.trim());
        user.setPasswordHash(encoder.encode(password));
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        users.save(user);
        return 1;
    }
}
