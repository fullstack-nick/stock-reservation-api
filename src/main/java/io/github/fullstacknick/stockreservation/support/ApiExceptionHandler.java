package io.github.fullstacknick.stockreservation.support;

import io.github.fullstacknick.stockreservation.reservation.domain.ReservationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Set<String> RETRIABLE_STATES = Set.of("55P03", "57014", "40P01", "40001");
    public record FieldIssue(String field, String message) {}

    @ExceptionHandler(ReservationException.class)
    ResponseEntity<Object> domain(ReservationException exception, WebRequest request) {
        HttpStatus status = switch (exception.reason()) {
            case INSUFFICIENT_STOCK, REQUEST_ID_CONFLICT -> HttpStatus.CONFLICT;
            case RESERVATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
        };
        var problem = problem(status, exception.reason().name(), exception.getMessage(), request);
        if (exception.field() != null) {
            problem.setProperty("errors", List.of(new FieldIssue(exception.field(), exception.getMessage())));
        }
        return respond(problem, new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        if (temporarilyUnavailable(exception)) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "1");
            return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
                    "The operation could not complete. Retry with the same request identity.", request), headers);
        }
        LoggerFactory.getLogger(ApiExceptionHandler.class).error("Unexpected request failure", exception);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error prevented the operation from completing.", request), new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = problem(status, "VALIDATION_FAILED", "One or more request fields are invalid.", request);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldIssue(error.getField(), error.getDefaultMessage()))
                .distinct().sorted(Comparator.comparing(FieldIssue::field).thenComparing(FieldIssue::message)).toList());
        return respond(problem, headers);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 400 -> "MALFORMED_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
        };
        return respond(problem(status, code, "The request could not be processed with the supplied HTTP contract.", request), headers);
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:stock-reservation:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        String title = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        problem.setTitle(Character.toUpperCase(title.charAt(0)) + title.substring(1));
        var servlet = ((ServletWebRequest) request).getRequest();
        problem.setInstance(URI.create(servlet.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", servlet.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        MDC.put("outcome", code);
        return problem;
    }

    private static ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders originalHeaders) {
        var headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(problem.getStatus()));
    }

    static boolean temporarilyUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransactionTimedOutException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (RETRIABLE_STATES.contains(state) || state.startsWith("08"))) return true;
            }
        }
        return false;
    }
}
