package io.github.panteliszara.issunexa.shared.web;

import io.github.panteliszara.issunexa.ticket.TicketNotFoundException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    @ExceptionHandler(TicketNotFoundException.class)
    public ProblemDetail handleTicketNotFound(TicketNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setType(ABOUT_BLANK);
        problem.setTitle("Ticket not found");
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Stream<FieldValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldValidationError(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value")));
        return handleExceptionInternal(exception, validationProblem(status, errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Stream<FieldValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldValidationError(
                                Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "parameter"),
                                Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value"))));
        return handleExceptionInternal(exception, validationProblem(status, errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Malformed or unreadable request parameter.");
        problem.setType(ABOUT_BLANK);
        problem.setTitle("Invalid request parameter");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Malformed or unreadable request body.");
        problem.setType(ABOUT_BLANK);
        problem.setTitle("Invalid request body");
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private static ProblemDetail validationProblem(HttpStatusCode status, Stream<FieldValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
        problem.setType(ABOUT_BLANK);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors
                .sorted(Comparator.comparing(FieldValidationError::field)
                        .thenComparing(FieldValidationError::message))
                .toList());
        return problem;
    }

    private record FieldValidationError(String field, String message) {
    }

}
