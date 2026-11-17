package io.github.zonarr.iou.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return respond(e.status(), e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(io.github.zonarr.iou.money.MoneyException.class)
    ResponseEntity<ApiError> money(io.github.zonarr.iou.money.MoneyException e) {
        return respond(HttpStatus.BAD_REQUEST, e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors()
                .forEach(g -> fields.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
        String message = fields.entrySet().stream().findFirst()
                .map(f -> f.getKey() + ": " + f.getValue()).orElse("Invalid request");
        return respond(HttpStatus.BAD_REQUEST, "validation_failed", message, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, "bad_request", "The request could not be read", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "conflict", "That conflicts with existing data", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> other(Exception e) throws Exception {
        if (e instanceof ErrorResponse standard) {
            // Spring's own 404/405/415... exceptions already know their status.
            HttpStatus status = HttpStatus.valueOf(standard.getStatusCode().value());
            return respond(status, status.name().toLowerCase(), status.getReasonPhrase(), null);
        }
        if (e instanceof org.springframework.security.core.AuthenticationException
                || e instanceof org.springframework.security.access.AccessDeniedException) {
            throw e; // let Spring Security answer with 401/403
        }
        log.error("Unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong", null);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
            Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), code, message, fields));
    }
}
