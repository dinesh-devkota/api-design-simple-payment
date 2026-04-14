package com.customercare.app.handler;

import com.customercare.domain.exception.AccountNotFoundException;
import com.customercare.domain.exception.InvalidPaymentAmountException;
import com.customercare.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Centralised exception → HTTP response mapping.
 *
 * <p>Handles domain exceptions ({@link AccountNotFoundException},
 * {@link InvalidPaymentAmountException}) and Spring validation exceptions.
 * {@link ErrorResponse} is generated from {@code openapi.yaml}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(InvalidPaymentAmountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentAmount(InvalidPaymentAmountException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred", null);
    }

    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status,
                                                         String message,
                                                         List<String> errors) {
        ErrorResponse body = new ErrorResponse()
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .errors(errors);
        return ResponseEntity.status(status).body(body);
    }
}

