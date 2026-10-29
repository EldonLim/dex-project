package com.dex.backend.api.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class ApiProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiProblemDetailsHandler.class);
    private static final URI INVALID_REQUEST_TYPE = URI.create("urn:dex:problem:invalid-request");
    private static final URI NOT_FOUND_TYPE = URI.create("urn:dex:problem:not-found");
    private static final URI INTERNAL_ERROR_TYPE = URI.create("urn:dex:problem:internal-error");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldError> errors = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .forEach(errors::add);
        exception.getBindingResult().getGlobalErrors().stream()
                .map(this::toGlobalError)
                .forEach(errors::add);

        return invalidRequest(request, errors);
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ProblemDetail> handleBindException(
            BindException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        return invalidRequest(request, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String parameterName = result.getMethodParameter().getParameterName();
                    String field = parameterName == null ? "parameter" : parameterName;

                    return result.getResolvableErrors().stream()
                            .map(error -> new ApiFieldError(
                                    field,
                                    defaultMessage(error.getDefaultMessage())
                            ));
                })
                .toList();

        return invalidRequest(request, errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiFieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldError(
                        lastPathSegment(violation.getPropertyPath().toString()),
                        defaultMessage(violation.getMessage())
                ))
                .toList();

        return invalidRequest(request, errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return invalidRequest(
                request,
                List.of(new ApiFieldError(exception.getName(), "has an invalid value"))
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return invalidRequest(
                request,
                List.of(new ApiFieldError(exception.getParameterName(), "is required"))
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_TYPE,
                "Resource not found",
                "The requested API resource was not found.",
                request
        );

        return response(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                INVALID_REQUEST_TYPE,
                "Method not allowed",
                "The requested HTTP method is not supported for this resource.",
                request
        );

        return response(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled API failure for {} {} ({})",
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getName()
        );

        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_TYPE,
                "Internal server error",
                "The request could not be completed.",
                request
        );

        return response(problem);
    }

    private ResponseEntity<ProblemDetail> invalidRequest(
            HttpServletRequest request,
            List<ApiFieldError> fieldErrors
    ) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST_TYPE,
                "Invalid request",
                "One or more request values are invalid.",
                request
        );
        List<ApiFieldError> sortedErrors = fieldErrors.stream()
                .sorted(Comparator.comparing(ApiFieldError::field)
                        .thenComparing(ApiFieldError::message))
                .toList();
        problem.setProperty("errors", sortedErrors);

        return response(problem);
    }

    private ProblemDetail problem(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private ApiFieldError toFieldError(FieldError error) {
        return new ApiFieldError(error.getField(), defaultMessage(error.getDefaultMessage()));
    }

    private ApiFieldError toGlobalError(ObjectError error) {
        return new ApiFieldError("request", defaultMessage(error.getDefaultMessage()));
    }

    private String defaultMessage(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    private String lastPathSegment(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
