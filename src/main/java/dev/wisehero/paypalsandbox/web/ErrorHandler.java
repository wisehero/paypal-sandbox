package dev.wisehero.paypalsandbox.web;

import dev.wisehero.paypalsandbox.checkout.OrderNotFoundException;
import dev.wisehero.paypalsandbox.fault.SimulatedDbFailureException;
import dev.wisehero.paypalsandbox.pg.PgDeclinedException;
import dev.wisehero.paypalsandbox.pg.PgTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ErrorHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(PgDeclinedException.class)
    ProblemDetail declined(PgDeclinedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, e.getMessage());
    }

    @ExceptionHandler(PgTimeoutException.class)
    ProblemDetail timeout(PgTimeoutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
    }

    /** 커넥션 풀에서 제한 시간 안에 커넥션을 얻지 못했다. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ProblemDetail noConnection(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "DB 커넥션을 얻지 못함: " + e.getMessage());
    }

    @ExceptionHandler(SimulatedDbFailureException.class)
    ProblemDetail dbFailure(SimulatedDbFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
