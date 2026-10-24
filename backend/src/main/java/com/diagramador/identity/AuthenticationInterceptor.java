package com.diagramador.identity;

import com.diagramador.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final String operatorToken;

    public AuthenticationInterceptor(IdentityService identityService, CurrentUserContext currentUserContext,
            @Value("${diagrammer.operator.token:}") String operatorToken) {
        this.identityService = identityService;
        this.currentUserContext = currentUserContext;
        this.operatorToken = operatorToken;
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
        if (operatorToken != null && !operatorToken.isBlank()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        operatorToken.getBytes(StandardCharsets.UTF_8))) {
            if (!isOperatorAllowed(request.getMethod(), path)) {
                currentUserContext.clear();
                throw forbiddenOperatorRequest();
            }
            currentUserContext.setOperator();
            return true;
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

    // The operator cannot create projects, run commands, or read model contents.
    private static boolean isOperatorAllowed(String method, String path) {
        if ("GET".equalsIgnoreCase(method) && "/projects".equals(path)) {
            return true;
        }

        String[] segments = path.split("/", -1);
        // S6: the platform operator acts as diagram administrator only for version-control endpoints;
        // commits and branches are the explicitly requested access, not a grant to edit/read live models.
        if (("GET".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method))
                && segments.length >= 5 && segments[0].isEmpty() && "projects".equals(segments[1])
                && isUuidPathSegment(segments[2]) && "vcs".equals(segments[3])) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && segments.length == 4 && segments[0].isEmpty()
                && "projects".equals(segments[1]) && isUuidPathSegment(segments[2])) {
            return "deployments".equals(segments[3]) || "generation-targets".equals(segments[3]);
        }
        if ("GET".equalsIgnoreCase(method) && segments.length == 6 && segments[0].isEmpty()
                && "projects".equals(segments[1]) && isUuidPathSegment(segments[2])
                && "generations".equals(segments[3]) && isUuidPathSegment(segments[4])
                && "artifacts".equals(segments[5])) {
            return true;
        }
        return "POST".equalsIgnoreCase(method) && segments.length == 4 && segments[0].isEmpty()
                && "deployments".equals(segments[1]) && isUuidPathSegment(segments[2])
                && ("claim".equals(segments[3]) || "report".equals(segments[3])
                        || "stop".equals(segments[3]));
    }

    private static boolean isUuidPathSegment(String segment) {
        try {
            return UUID.fromString(segment).toString().equalsIgnoreCase(segment);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static ApiException forbiddenOperatorRequest() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The operator principal is not allowed to access this endpoint.", List.of(), null);
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
