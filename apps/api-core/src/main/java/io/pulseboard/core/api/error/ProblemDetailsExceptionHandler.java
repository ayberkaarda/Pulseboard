package io.pulseboard.core.api.error;

import io.pulseboard.core.application.auth.AuthenticationFailedException;
import io.pulseboard.core.application.common.DependencyUnavailableException;
import io.pulseboard.core.application.common.InvalidRequestException;
import io.pulseboard.core.application.common.ResourceConflictException;
import io.pulseboard.core.application.common.ResourceNotFoundException;
import io.pulseboard.core.application.ingest.PayloadTooLargeException;
import io.pulseboard.core.application.ratelimit.RateLimitExceededException;
import io.pulseboard.core.application.ratelimit.RateLimiterUnavailableException;
import io.pulseboard.core.domain.metrics.AggregateOverflowException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 {@code application/problem+json} document. Spring MVC's own exceptions
 * (malformed JSON, type mismatches, unknown routes) are handled by the {@link ResponseEntityExceptionHandler}
 * base class; security failures from the filter chain are routed here as well.
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:pulseboard:problem:";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(ResourceConflictException.class)
    ProblemDetail handleConflict(ResourceConflictException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        LOG.debug("Data integrity violation", ex);
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict",
                "The request conflicts with the current state of the resource");
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ProblemDetail> handleAuthenticationFailed(AuthenticationFailedException ex) {
        return unauthorized(ex.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        // Only messages written by this application are echoed; library messages may describe token internals.
        if (ex instanceof BadCredentialsException && ex.getMessage() != null) {
            return unauthorized(ex.getMessage());
        }
        return unauthorized("Authentication is required or the presented credentials are invalid");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too Many Requests",
                        "Too many requests; retry after the time given in the Retry-After header"));
    }

    @ExceptionHandler(RateLimiterUnavailableException.class)
    ResponseEntity<ProblemDetail> handleRateLimiterUnavailable(RateLimiterUnavailableException ex) {
        LOG.error("Rate limiter unavailable; rejecting throttled request", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Service Unavailable",
                        "The service is temporarily unavailable"));
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ProblemDetail> handleDependencyUnavailable(DependencyUnavailableException ex) {
        LOG.error("Required dependency unavailable; failing closed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "Service Unavailable",
                        "The service is temporarily unavailable"));
    }

    /** Lock conflicts (deadlock victim, lock timeout) are transient: the client should simply retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        LOG.warn("Concurrent write conflict; answering with a retryable 503", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "concurrency-conflict", "Service Unavailable",
                        "The request conflicted with a concurrent write and was rolled back; retry it"));
    }

    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ProblemDetail> handleQueryTimeout(QueryTimeoutException ex) {
        LOG.warn("Query cancelled by the statement timeout", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "query-timeout", "Service Unavailable",
                        "The query took too long; narrow the range or use a larger step"));
    }

    @ExceptionHandler(AggregateOverflowException.class)
    ProblemDetail handleAggregateOverflow(AggregateOverflowException ex) {
        LOG.warn("Aggregate overflow", ex);
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "aggregate-out-of-range", "Unprocessable Content",
                "An aggregate of the stored samples exceeds the representable range");
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", ex.getMessage());
        body.setProperty("errors", ex.getViolations().stream()
                .map(violation -> Map.of("field", violation.location(), "message", violation.message()))
                .toList());
        return body;
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "Content Too Large", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "The authenticated principal is not allowed to perform this operation");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        LOG.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setType(URI.create(TYPE_PREFIX + "validation"));
        body.setTitle("Validation failed");
        body.setDetail("The request body is invalid");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(ProblemDetailsExceptionHandler::fieldError)
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> unauthorized(String detail) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", detail));
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + type));
        problem.setTitle(title);
        return problem;
    }
}
