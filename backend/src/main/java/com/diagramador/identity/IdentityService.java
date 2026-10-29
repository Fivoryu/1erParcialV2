package com.diagramador.identity;

import com.diagramador.api.ApiException;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.UserSession;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.UserSessionRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");
    // Unknown usernames still take one BCrypt path so login timing does not disclose account existence.
    private static final String DUMMY_BCRYPT_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserAccountRepository userAccountRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordHasher passwordHasher;
    private final SessionTokenFactory tokenFactory;

    public IdentityService(UserAccountRepository userAccountRepository, UserSessionRepository userSessionRepository,
            PasswordHasher passwordHasher, SessionTokenFactory tokenFactory) {
        this.userAccountRepository = userAccountRepository;
        this.userSessionRepository = userSessionRepository;
        this.passwordHasher = passwordHasher;
        this.tokenFactory = tokenFactory;
    }

    @Transactional
    public UserAccount register(String username, String password, String displayName, String email) {
        String normalizedUsername = username == null ? null : username.trim();
        if (normalizedUsername == null || !USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw invalidPayload("username must be 3 to 64 characters and contain only letters, numbers, dots, "
                    + "underscores, or hyphens.");
        }
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw invalidPayload("password must be between 8 and 200 characters.");
        }
        String normalizedDisplayName = displayName == null ? null : displayName.trim();
        if (normalizedDisplayName == null || normalizedDisplayName.isEmpty() || normalizedDisplayName.length() > 160) {
            throw invalidPayload("displayName must be between 1 and 160 characters.");
        }
        String normalizedEmail = email == null ? null : email.trim();
        if (normalizedEmail != null && normalizedEmail.isEmpty()) {
            normalizedEmail = null;
        }
        if (normalizedEmail != null && normalizedEmail.length() > 320) {
            throw invalidPayload("email must be at most 320 characters.");
        }
        if (normalizedEmail != null && !normalizedEmail.contains("@")) {
            throw invalidPayload("email must contain @ when provided.");
        }
        if (userAccountRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "That username is already taken.",
                    List.of(), null);
        }

        UserAccount user = UserAccount.register(normalizedUsername, passwordHasher.hash(password), normalizedDisplayName,
                normalizedEmail);
        return userAccountRepository.save(user);
    }

    @Transactional
    public LoginResult login(String username, String password) {
        String normalizedUsername = username == null ? null : username.trim();
        String passwordToCheck = password == null ? "" : password;
        Optional<UserAccount> candidate = normalizedUsername == null || normalizedUsername.isEmpty()
                ? Optional.empty()
                : userAccountRepository.findByUsernameIgnoreCase(normalizedUsername);
        if (candidate.isEmpty()) {
            passwordHasher.matches(passwordToCheck, DUMMY_BCRYPT_HASH);
            throw invalidCredentials();
        }

        UserAccount user = candidate.get();
        boolean passwordMatches = passwordHasher.matches(passwordToCheck, user.getPasswordHash());
        if (password == null || password.isBlank() || !passwordMatches || !isActive(user)) {
            throw invalidCredentials();
        }

        String token = tokenFactory.createToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(12);
        userSessionRepository.save(UserSession.create(user, tokenFactory.sha256Hex(token), expiresAt));
        return new LoginResult(token, expiresAt, user);
    }

    @Transactional
    public CurrentUser resolve(String token) {
        if (token == null || token.isBlank()) {
            throw unauthenticated();
        }
        UserSession session = userSessionRepository.findByTokenHash(tokenFactory.sha256Hex(token))
                .orElseThrow(IdentityService::unauthenticated);
        OffsetDateTime now = OffsetDateTime.now();
        UserAccount user = session.getUser();
        if (session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now) || !isActive(user)) {
            throw unauthenticated();
        }
        session.setLastSeenAt(now);
        userSessionRepository.save(session);
        return new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName());
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        userSessionRepository.findByTokenHash(tokenFactory.sha256Hex(token)).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(OffsetDateTime.now());
                userSessionRepository.save(session);
            }
        });
    }

    private static boolean isActive(UserAccount user) {
        return user != null && "ACTIVE".equals(user.getStatus()) && user.getRevokedAt() == null;
    }

    private static ApiException invalidPayload(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "El usuario o la contraseña son incorrectos.", List.of(), null);
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.",
                List.of(), null);
    }

    public record LoginResult(String token, OffsetDateTime expiresAt, UserAccount user) {
    }
}
