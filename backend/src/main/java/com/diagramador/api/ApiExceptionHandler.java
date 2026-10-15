package com.diagramador.api;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResource> handleApiException(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details(),
                exception.operationId());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResource> handleInvalidBody(MethodArgumentNotValidException exception) {
        List<Map<String, Object>> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.<String, Object>of("field", error.getField(), "reason", error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", "Request body is invalid.", details, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingPathVariableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiErrorResource> handleMalformedRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD",
                "Request body or required path parameter is invalid.", List.of(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResource> handleUnknownRoute(NoResourceFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource was not found.", List.of(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResource> handleUnexpectedFailure(Exception exception) {
        // A swallowed 500 is undiagnosable; the stack is the only trace the operator gets.
        logger.error("Unexpected server error", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected server error occurred.",
                List.of(), null);
    }

    private ResponseEntity<ApiErrorResource> response(HttpStatus status, String code, String message,
            List<Map<String, Object>> details, java.util.UUID operationId) {
        return ResponseEntity.status(status).body(new ApiErrorResource(code, message, details, operationId));
    }
}
