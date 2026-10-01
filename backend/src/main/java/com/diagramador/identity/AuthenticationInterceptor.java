package com.diagramador.identity;

import com.diagramador.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/** Resolves bearer credentials and binds the authenticated user to the request thread. */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final String AUTH_REGISTER_PATH = "/auth/register";
    private static final String AUTH_LOGIN_PATH = "/auth/login";
    private static final String ACTUATOR_PATH = "/actuator";

    private final IdentityService identityService;
    private final CurrentUserContext currentUserContext;

    public AuthenticationInterceptor(IdentityService identityService, CurrentUserContext currentUserContext) {
        this.identityService = identityService;
        this.currentUserContext = currentUserContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        currentUserContext.clear();
        // A CORS preflight never carries credentials: it is the request that asks permission to send them.
        // The browser sends it before the real call, so requiring a token here would reject every
        // cross-origin request from the editor and no request would ever reach the API.
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isPublic(request.getMethod(), path)) {
            return true;
        }

        String token = bearerToken(request.getHeader("Authorization"));
        if (token == null) {
            throw unauthenticated();
        }
        try {
            currentUserContext.set(identityService.resolve(token));
            return true;
        } catch (ApiException exception) {
            currentUserContext.clear();
            throw unauthenticated();
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        try {
            // The context is request-scoped even when a controller or another interceptor fails.
        } finally {
            currentUserContext.clear();
        }
    }

    private static boolean isPublic(String method, String path) {
        return ("POST".equalsIgnoreCase(method)
                && (AUTH_REGISTER_PATH.equals(path) || AUTH_LOGIN_PATH.equals(path)))
                || ACTUATOR_PATH.equals(path) || path.startsWith(ACTUATOR_PATH + "/");
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        int separator = authorization.indexOf(' ');
        if (separator < 0 || !"Bearer".equalsIgnoreCase(authorization.substring(0, separator))) {
            return null;
        }
        String token = authorization.substring(separator + 1).trim();
        return token.isEmpty() ? null : token;
    }

    private static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.",
                List.of(), null);
    }
}
