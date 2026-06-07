package com.recrutment.auditservice.config;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates uncaught exceptions into a clean JSON envelope so clients
 * never see a raw Spring stack trace. Without this, a NullPointer or
 * a JPA constraint violation responds with a Whitelabel HTML page that
 * leaks class names and framework versions.
 *
 * Each handler returns:
 *   { "timestamp": "...", "status": 4xx|5xx, "error": "short label",
 *     "message": "what happened", "path": "/api/..." }
 *
 * Plus a "details" map for validation errors so the SPA can highlight
 * individual fields.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Handlers that already chose their own status via ResponseStatusException
     *  pass through with the status they set. We just strip the stack. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex,
                                                              HttpServletRequest req) {
        return body(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), req);
    }

    /** Bean validation failures triggered by @Valid on a @RequestBody. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest req) {
        Map<String, Object> b = baseBody(HttpStatus.BAD_REQUEST, "Validation failed", req);
        Map<String, String> fields = new LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        b.put("details", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(b);
    }

    /** Hibernate / JPA unique-constraint, FK, or NOT NULL violations. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException ex,
                                                         HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, "Data integrity violation", req);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(EntityNotFoundException ex,
                                                        HttpServletRequest req) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest req) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException ex,
                                                            HttpServletRequest req) {
        return body(HttpStatus.FORBIDDEN, "Access denied", req);
    }

    /** Anything that escapes the controllers. Logged with the full stack
     *  for ops; the client sees a generic message. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception ex, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", req);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
                                                            HttpServletRequest req) {
        return ResponseEntity.status(status).body(baseBody(status, message, req));
    }

    private static Map<String, Object> baseBody(HttpStatus status, String message, HttpServletRequest req) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("timestamp", OffsetDateTime.now().toString());
        b.put("status", status.value());
        b.put("error", status.getReasonPhrase());
        b.put("message", message != null ? message : status.getReasonPhrase());
        b.put("path", req.getRequestURI());
        return b;
    }
}
