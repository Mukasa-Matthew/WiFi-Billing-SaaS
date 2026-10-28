package com.wifibilling.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One JSON error shape for every API failure:
 * {@code {timestamp, status, error, message, path[, reference]}}.
 *
 * Spring Boot's default error body drops the reason given to
 * ResponseStatusException, so users saw "Request failed" instead of e.g.
 * "Router not found". Reasons thrown by our code are written for users and
 * are returned as {@code message}. Unexpected exceptions never expose their
 * text (it may carry provider responses or internals): the client gets a
 * generic message with a reference that matches the server log line.
 *
 * Spring MVC's own exceptions keep their standard statuses via the
 * ResponseEntityExceptionHandler base class. Authentication and access
 * failures raised by the security filter chain never reach this class.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        // ResponseStatusException arrives with body == null; its reason lives on the exception.
        String detail = ex instanceof ResponseStatusException rse ? rse.getReason()
                : body instanceof ProblemDetail pd ? pd.getDetail()
                : ex instanceof ErrorResponse er ? er.getBody().getDetail()
                : null;
        String message = detail != null && !detail.isBlank() ? detail : reasonPhrase(status);
        String reference = null;
        if (status.is5xxServerError()) {
            reference = newReference();
            log.warn("API error {} ref={} {}: {}", status.value(), reference, path(request), message);
        }
        return ResponseEntity.status(status).headers(headers).body(errorBody(status, message, path(request), reference));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        FieldError field = ex.getBindingResult().getFieldError();
        String message = field != null ? field.getField() + ": " + field.getDefaultMessage() : "Some of the submitted details are invalid.";
        return ResponseEntity.status(status).headers(headers).body(errorBody(status, message, path(request), null));
    }

    /** Method-level security (@PreAuthorize) denials stay 403, not 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(HttpStatus.FORBIDDEN, "You don't have permission to do that.", request.getRequestURI(), null));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(errorBody(HttpStatus.UNAUTHORIZED, "Please sign in again.", request.getRequestURI(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        String reference = newReference();
        log.error("Unhandled API error ref={} {} {}", reference, request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Something went wrong on our side. Please try again. If it keeps happening, contact support with reference " + reference + ".",
                        request.getRequestURI(), reference));
    }

    private static Map<String, Object> errorBody(HttpStatusCode status, String message, String path, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", reasonPhrase(status));
        body.put("message", message);
        body.put("path", path);
        if (reference != null) body.put("reference", reference);
        return body;
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
    }

    private static String newReference() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
