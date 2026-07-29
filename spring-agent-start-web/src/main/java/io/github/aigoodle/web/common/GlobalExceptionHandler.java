package io.github.aigoodle.web.common;

import io.github.aigoodle.common.exception.AgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every exception into the {@link ApiResponse} envelope with an
 * {@link ApiErrorCode} enum-slug, HTTP status, message and correlation
 * {@code traceId}. Runs under Spring MVC.
 *
 * <p>Every response carries a {@code traceId} — logged server-side alongside
 * the stack trace so support can pull the exact request that failed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String MDC_TRACE_ID = "traceId";

    private static String currentTraceId() {
        String existing = MDC.get(MDC_TRACE_ID);
        if (existing != null && !existing.isBlank()) return existing;
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ResponseEntity<ApiResponse<?>> build(ApiErrorCode code, String message,
                                                  Map<String, Object> details) {
        String traceId = currentTraceId();
        ApiResponse<?> body = ApiResponse.error(code, message == null ? code.getDefaultMessage() : message)
                .withTraceId(traceId);
        if (details != null && !details.isEmpty()) {
            body.withDetails(details);
        }
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ApiResponse<?>> handleAgent(AgentException ex) {
        ApiErrorCode code = ApiErrorCode.fromSlug(ex.getCode());
        if (code == null) {
            log.info("Untriaged AgentException slug '{}' — fallback bad_request", ex.getCode());
            code = ApiErrorCode.BAD_REQUEST;
        }
        log.info("AgentException [{}]: {}", ex.getCode(), ex.getMessage());
        return build(code, ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleIllegal(IllegalArgumentException ex) {
        return build(ApiErrorCode.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                details.put(fe.getField(), fe.getDefaultMessage()));
        String msg = ex.getBindingResult().getFieldErrors().stream().findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse(ex.getMessage());
        return build(ApiErrorCode.VALIDATION_FAILED, msg, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<?>> handleInput(Exception ex) {
        return build(ApiErrorCode.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<?>> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ApiErrorCode code = switch (status) {
            case UNAUTHORIZED -> ApiErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ApiErrorCode.FORBIDDEN;
            case NOT_FOUND -> ApiErrorCode.NOT_FOUND;
            case CONFLICT -> ApiErrorCode.CONFLICT;
            case TOO_MANY_REQUESTS -> ApiErrorCode.RATE_LIMITED;
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> ApiErrorCode.UPSTREAM_UNAVAILABLE;
            default -> ApiErrorCode.SERVER_ERROR;
        };
        return build(code, ex.getReason(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleAll(Exception ex) {
        String traceId = currentTraceId();
        log.error("Unhandled exception traceId={}: {}", traceId, ex.getMessage(), ex);
        ApiResponse<?> body = ApiResponse.error(ApiErrorCode.SERVER_ERROR,
                        "Internal server error. Reference: " + traceId)
                .withTraceId(traceId);
        return ResponseEntity.status(ApiErrorCode.SERVER_ERROR.getStatus()).body(body);
    }
}
