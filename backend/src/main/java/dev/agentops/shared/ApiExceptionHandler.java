package dev.agentops.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiError.class)
    ProblemDetail api(ApiError error, HttpServletRequest request) { return Problems.detail(error.status(), error.code(), request); }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ProblemDetail validation(Exception error, HttpServletRequest request) { return Problems.detail(400, "VALIDATION_FAILED", request); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(HttpServletRequest request) { return Problems.detail(409, "RESOURCE_CONFLICT", request); }
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(HttpServletRequest request) { return Problems.detail(403, "FORBIDDEN", request); }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    ProblemDetail notFound(HttpServletRequest request) { return Problems.detail(404, "RESOURCE_NOT_FOUND", request); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpServletRequest request) { return Problems.detail(405, "METHOD_NOT_ALLOWED", request); }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ProblemDetail mediaType(HttpServletRequest request) { return Problems.detail(415, "UNSUPPORTED_MEDIA_TYPE", request); }
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    ProblemDetail database(HttpServletRequest request) { return Problems.detail(503, "DEPENDENCY_UNAVAILABLE", request); }
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Request failed: type={} traceId={}", error.getClass().getSimpleName(), RequestIds.of(request));
        return Problems.detail(500, "INTERNAL_ERROR", request);
    }
}
