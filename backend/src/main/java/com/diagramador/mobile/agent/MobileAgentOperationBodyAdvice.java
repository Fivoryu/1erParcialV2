package com.diagramador.mobile.agent;

import com.diagramador.api.ApiException;
import com.diagramador.api.ApiErrorResource;
import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.identity.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Caps the complete structured envelope before parsing, including chunked requests. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = MobileAgentOperationController.class)
public class MobileAgentOperationBodyAdvice extends RequestBodyAdviceAdapter {
    private static final int MAX_BYTES = 65_536;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MobileAgentOperationBodyAdvice.class);
    private final CurrentUserContext users;
    private final ApiExceptionHandler errors;
    public MobileAgentOperationBodyAdvice(CurrentUserContext users, ApiExceptionHandler errors) {
        this.users = users;
        this.errors = errors;
    }
    @Override public boolean supports(MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
            Class<? extends HttpMessageConverter<?>> converter) {
        if (input.getHeaders().getContentLength() > MAX_BYTES) throw tooLarge();
        return new HttpInputMessage() {
            @Override public org.springframework.http.HttpHeaders getHeaders() { return input.getHeaders(); }
            @Override public InputStream getBody() throws IOException {
                return new FilterInputStream(input.getBody()) {
                    private int count;
                    private void add(int amount) { count += amount; if (count > MAX_BYTES) throw tooLarge(); }
                    @Override public int read() throws IOException { int value = in.read(); if (value != -1) add(1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int value = in.read(bytes, offset, length); if (value > 0) add(value); return value;
                    }
                };
            }
        };
    }
    private ApiException tooLarge() {
        users.optional().ifPresent(user -> LOG.info("mobile_agent_operation userId={} event=rejected code=OPERATION_BODY_TOO_LARGE", user.userId()));
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "OPERATION_BODY_TOO_LARGE", "The operation envelope is too large.", List.of(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResource> invalidBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<?, ?> variables = attribute instanceof Map<?, ?> map ? map : Map.of();
        users.optional().ifPresent(user -> LOG.info(
                "mobile_agent_operation userId={} projectId={} deploymentId={} method={} route={} status={} event=rejected",
                user.userId(), identifier(variables.get("projectId")), identifier(variables.get("deploymentId")),
                request.getMethod(), "/projects/{projectId}/mobile/backends/{deploymentId}/agent/operations", HttpStatus.BAD_REQUEST.value()));
        return errors.handleInvalidBody(exception);
    }

    private static UUID identifier(Object value) {
        if (!(value instanceof String text)) return null;
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException invalidIdentifier) { return null; }
    }
}
