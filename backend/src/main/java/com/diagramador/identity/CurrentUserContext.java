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
    private final ThreadLocal<Boolean> currentOperator = new ThreadLocal<>();

    public void set(CurrentUser user) {
        currentUser.set(user);
        currentOperator.remove();
    }

    public void setOperator() {
        currentUser.remove();
        currentOperator.set(true);
    }

    public boolean isOperator() {
        return Boolean.TRUE.equals(currentOperator.get());
    }

    public void requireOperator() {
        if (isOperator()) {
            return;
        }
        if (currentUser.get() == null) {
            throw unauthenticated();
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "The operator principal is required.",
                List.of(), null);
    }

    public void clear() {
        currentUser.remove();
        currentOperator.remove();
    }

    public CurrentUser require() {
        return optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required.", List.of(), null));
    }

    public Optional<CurrentUser> optional() {
        return Optional.ofNullable(currentUser.get());
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.",
                List.of(), null);
    }
}
