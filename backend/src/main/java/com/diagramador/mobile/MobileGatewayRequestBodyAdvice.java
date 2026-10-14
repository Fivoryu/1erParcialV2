package com.diagramador.mobile;

import com.diagramador.api.ApiErrorResource;
import com.diagramador.api.ApiExceptionHandler;
import com.diagramador.identity.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdvice;

/** Applies a hard cap before Jackson parses a chunked or content-length-free mobile operation body. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = MobileGatewayController.class)
public class MobileGatewayRequestBodyAdvice implements RequestBodyAdvice {
    static final int MAX_INBOUND_BYTES = 65_536;
    private static final Logger LOG = LoggerFactory.getLogger(MobileGatewayRequestBodyAdvice.class);
    private final CurrentUserContext users;
    private final ApiExceptionHandler errors;

    public MobileGatewayRequestBodyAdvice(CurrentUserContext users, ApiExceptionHandler errors) {
        this.users = users;
        this.errors = errors;
    }
    @Override public boolean supports(MethodParameter parameter, java.lang.reflect.Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) { return true; }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
            java.lang.reflect.Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        long length = input.getHeaders().getContentLength();
        if (length > MAX_INBOUND_BYTES) throw new MobileGatewayPayloadTooLargeException();
        return new HttpInputMessage() {
            @Override public org.springframework.http.HttpHeaders getHeaders() { return input.getHeaders(); }
            @Override public InputStream getBody() throws IOException { return new LimitedInputStream(input.getBody()); }
        };
    }
    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
            java.lang.reflect.Type targetType, Class<? extends HttpMessageConverter<?>> converterType) { return body; }
    @Override public Object handleEmptyBody(Object body, HttpInputMessage input, MethodParameter parameter,
            java.lang.reflect.Type targetType, Class<? extends HttpMessageConverter<?>> converterType) { return body; }
    @ExceptionHandler(MobileGatewayPayloadTooLargeException.class)
    ResponseEntity<java.util.Map<String, String>> payloadTooLarge(HttpServletRequest request) {
        auditRejection(request, HttpStatus.PAYLOAD_TOO_LARGE.value());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(java.util.Map.of("code", "OPERATION_BODY_TOO_LARGE"));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResource> invalidBody(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        auditRejection(request, HttpStatus.BAD_REQUEST.value());
        return errors.handleInvalidBody(exception);
    }

    private void auditRejection(HttpServletRequest request, int status) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Map<?, ?> variables = attribute instanceof Map<?, ?> map ? map : Map.of();
        users.optional().ifPresent(user -> LOG.info(
                "mobile_gateway userId={} projectId={} deploymentId={} method={} route={} status={}",
                user.userId(), identifier(variables.get("projectId")), identifier(variables.get("deploymentId")),
                request.getMethod(), "/projects/{projectId}/mobile/backends/{deploymentId}/operations", status));
    }

    private UUID identifier(Object value) {
        if (!(value instanceof String text)) return null;
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int count;
        LimitedInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException { int value = super.read(); if (value != -1) increment(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { int value = super.read(bytes, offset, length); if (value > 0) increment(value); return value; }
        private void increment(int value) { count += value; if (count > MAX_INBOUND_BYTES) throw new MobileGatewayPayloadTooLargeException(); }
    }
    static final class MobileGatewayPayloadTooLargeException extends RuntimeException { }
}
