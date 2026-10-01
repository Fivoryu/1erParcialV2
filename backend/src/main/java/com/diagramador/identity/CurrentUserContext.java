package com.diagramador.identity;

import com.diagramador.api.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Holds authenticated user details for the lifetime of one request thread. */
@Component
public class CurrentUserContext {

    private final ThreadLocal<CurrentUser> currentUser = new ThreadLocal<>();

    public void set(CurrentUser user) {
        currentUser.set(user);
    }

    public void clear() {
        currentUser.remove();
    }

    public CurrentUser require() {
        return optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required.", List.of(), null));
    }

    public Optional<CurrentUser> optional() {
        return Optional.ofNullable(currentUser.get());
    }
}
