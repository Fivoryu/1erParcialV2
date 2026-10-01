package com.diagramador.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramador.api.ApiException;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.UserSession;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.UserSessionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IdentityServiceTest {

    private UserAccountRepository userAccountRepository;
    private UserSessionRepository userSessionRepository;
    private PasswordHasher passwordHasher;
    private SessionTokenFactory tokenFactory;
    private IdentityService identityService;

    @BeforeEach
    void setUp() {
        userAccountRepository = org.mockito.Mockito.mock(UserAccountRepository.class);
        userSessionRepository = org.mockito.Mockito.mock(UserSessionRepository.class);
        passwordHasher = org.mockito.Mockito.mock(PasswordHasher.class);
        tokenFactory = org.mockito.Mockito.mock(SessionTokenFactory.class);
        identityService = new IdentityService(userAccountRepository, userSessionRepository, passwordHasher,
                tokenFactory);
    }

    @Test
    void registerTrimsIdentityFieldsAndHashesThePassword() {
        when(userAccountRepository.existsByUsernameIgnoreCase("new.user")).thenReturn(false);
        when(passwordHasher.hash("secure-password")).thenReturn("bcrypt-hash");
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount user = identityService.register("  new.user  ", "secure-password", "  New User  ",
                "  new@example.test  ");

        assertThat(user.getUsername()).isEqualTo("new.user");
        assertThat(user.getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(user.getDisplayName()).isEqualTo("New User");
        assertThat(user.getEmail()).isEqualTo("new@example.test");
        verify(passwordHasher).hash("secure-password");
    }

    @Test
    void registerRejectsAnInvalidUsername() {
        assertApiError(() -> identityService.register("bad name", "secure-password", "New User", null),
                HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
        verify(userAccountRepository, never()).existsByUsernameIgnoreCase(anyString());
    }

    @Test
    void registerRejectsInvalidEmail() {
        assertApiError(() -> identityService.register("valid.user", "secure-password", "New User", "not-an-email"),
                HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD");
    }

    @Test
    void registerRejectsDuplicateUsernamesIgnoringCase() {
        when(userAccountRepository.existsByUsernameIgnoreCase("Taken.User")).thenReturn(true);

        assertThatThrownBy(() -> identityService.register(" Taken.User ", "secure-password", "New User", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("USERNAME_TAKEN");
                    assertThat(exception.getMessage()).isEqualTo("That username is already taken.");
                });
    }

    @Test
    void loginRejectsAWrongPasswordWithTheGenericCredentialError() {
        UserAccount user = UserAccount.register("existing.user", "stored-hash", "Existing User", null);
        when(userAccountRepository.findByUsernameIgnoreCase("existing.user")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong-password", "stored-hash")).thenReturn(false);

        assertApiError(() -> identityService.login("existing.user", "wrong-password"), HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS");
    }

    @Test
    void loginChecksTheDummyHashWhenTheUsernameIsUnknown() {
        when(userAccountRepository.findByUsernameIgnoreCase("missing.user")).thenReturn(Optional.empty());
        when(passwordHasher.matches(eq("some-password"), anyString())).thenReturn(false);

        assertApiError(() -> identityService.login("missing.user", "some-password"), HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS");

        verify(passwordHasher, times(1)).matches(eq("some-password"), anyString());
    }

    @Test
    void loginTreatsMissingCredentialsAsInvalidCredentials() {
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        assertApiError(() -> identityService.login("  ", null), HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");

        verify(passwordHasher).matches(eq(""), anyString());
    }

    @Test
    void successfulLoginPersistsOnlyTheTokenHashAndReturnsTheRawToken() {
        UserAccount user = UserAccount.register("existing.user", "stored-hash", "Existing User", null);
        when(userAccountRepository.findByUsernameIgnoreCase("existing.user")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("correct-password", "stored-hash")).thenReturn(true);
        when(tokenFactory.createToken()).thenReturn("raw-bearer-token");
        when(tokenFactory.sha256Hex("raw-bearer-token")).thenReturn("token-hash");

        IdentityService.LoginResult result = identityService.login("existing.user", "correct-password");

        assertThat(result.token()).isEqualTo("raw-bearer-token");
        assertThat(result.expiresAt()).isAfter(OffsetDateTime.now().plusHours(11));
        assertThat(result.expiresAt()).isBefore(OffsetDateTime.now().plusHours(13));
        assertThat(result.user()).isSameAs(user);
        org.mockito.ArgumentCaptor<UserSession> session = org.mockito.ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionRepository).save(session.capture());
        assertThat(session.getValue().getTokenHash()).isEqualTo("token-hash");
        assertThat(session.getValue().getExpiresAt()).isEqualTo(result.expiresAt());
    }

    @Test
    void resolveRejectsARevokedSession() {
        UserAccount user = UserAccount.register("existing.user", "stored-hash", "Existing User", null);
        UserSession session = UserSession.create(user, "token-hash", OffsetDateTime.now().plusHours(1));
        session.setRevokedAt(OffsetDateTime.now());
        when(tokenFactory.sha256Hex("token")).thenReturn("token-hash");
        when(userSessionRepository.findByTokenHash("token-hash")).thenReturn(Optional.of(session));

        assertApiError(() -> identityService.resolve("token"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void resolveRejectsAnExpiredSession() {
        UserAccount user = UserAccount.register("existing.user", "stored-hash", "Existing User", null);
        UserSession session = UserSession.create(user, "token-hash", OffsetDateTime.now().minusSeconds(1));
        when(tokenFactory.sha256Hex("token")).thenReturn("token-hash");
        when(userSessionRepository.findByTokenHash("token-hash")).thenReturn(Optional.of(session));

        assertApiError(() -> identityService.resolve("token"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    void logoutIsIdempotentForUnknownAndAlreadyRevokedSessions() {
        when(tokenFactory.sha256Hex("unknown-token")).thenReturn("unknown-hash");
        when(userSessionRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());
        identityService.logout("unknown-token");
        identityService.logout("unknown-token");

        UserAccount user = UserAccount.register("existing.user", "stored-hash", "Existing User", null);
        UserSession session = UserSession.create(user, "revoked-hash", OffsetDateTime.now().plusHours(1));
        OffsetDateTime revokedAt = OffsetDateTime.now().minusMinutes(1);
        session.setRevokedAt(revokedAt);
        when(tokenFactory.sha256Hex("revoked-token")).thenReturn("revoked-hash");
        when(userSessionRepository.findByTokenHash("revoked-hash")).thenReturn(Optional.of(session));

        identityService.logout("revoked-token");
        identityService.logout("revoked-token");

        assertThat(session.getRevokedAt()).isEqualTo(revokedAt);
        verify(userSessionRepository, never()).save(any(UserSession.class));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus status,
            String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.status()).isEqualTo(status);
            assertThat(exception.code()).isEqualTo(code);
        });
    }
}
