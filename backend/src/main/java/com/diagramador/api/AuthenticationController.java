package com.diagramador.api;

import static com.diagramador.api.AuthResources.LoginRequest;
import static com.diagramador.api.AuthResources.LoginResource;
import static com.diagramador.api.AuthResources.RegisterRequest;
import static com.diagramador.api.AuthResources.UserResource;

import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.IdentityService;
import com.diagramador.identity.IdentityService.LoginResult;
import com.diagramador.persistence.UserAccountRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthenticationController {

    private final IdentityService identityService;
    private final CurrentUserContext currentUserContext;
    private final UserAccountRepository userAccountRepository;

    public AuthenticationController(IdentityService identityService, CurrentUserContext currentUserContext,
            UserAccountRepository userAccountRepository) {
        this.identityService = identityService;
        this.currentUserContext = currentUserContext;
        this.userAccountRepository = userAccountRepository;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResource> register(@Valid @RequestBody RegisterRequest request) {
        UserAccount user = identityService.register(request.username(), request.password(), request.displayName(),
                request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(userResource(user));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LoginResource login(@RequestBody LoginRequest request) {
        LoginResult result = identityService.login(request.username(), request.password());
        return new LoginResource(result.token(), result.expiresAt(), userResource(result.user()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        identityService.logout(requireBearerToken(authorization));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResource me() {
        CurrentUser currentUser = currentUserContext.require();
        String email = userAccountRepository.findById(currentUser.userId()).map(UserAccount::getEmail).orElse(null);
        return new UserResource(currentUser.userId(), currentUser.username(), currentUser.displayName(), email);
    }

    private static UserResource userResource(UserAccount user) {
        return new UserResource(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail());
    }

    private static String requireBearerToken(String authorization) {
        if (authorization != null) {
            int separator = authorization.indexOf(' ');
            if (separator >= 0 && "Bearer".equalsIgnoreCase(authorization.substring(0, separator))) {
                String token = authorization.substring(separator + 1).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.",
                List.of(), null);
    }
}
