package io.x402.paypal.web;

import java.util.List;
import java.util.Map;

import io.x402.paypal.enrollment.EnrollmentPendingException;
import io.x402.paypal.paypal.PayPalApiException;
import io.x402.paypal.premium.PremiumController;
import io.x402.paypal.x402.X402Exception;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_ERROR",
                "details", errors
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", exception.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", exception.getMessage()
        ));
    }

    @ExceptionHandler(X402Exception.class)
    public ResponseEntity<Map<String, Object>> paymentRequired(X402Exception exception) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", exception.code());
        body.put("message", exception.getMessage());
        if (exception.attemptId() != null) {
            body.put("attemptId", exception.attemptId());
        }
        if (exception.payerActionUrl() != null) {
            body.put("payerActionUrl", exception.payerActionUrl());
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED);
        if (exception.paymentRequiredHeader() != null) {
            builder.header(PremiumController.PAYMENT_REQUIRED_HEADER, exception.paymentRequiredHeader());
        }
        return builder.body(body);
    }

    @ExceptionHandler(EnrollmentPendingException.class)
    public ResponseEntity<Map<String, Object>> enrollmentPending(EnrollmentPendingException exception) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "ENROLLMENT_PENDING");
        body.put("message", exception.getMessage());
        if (exception.approveUrl() != null) {
            body.put("approveUrl", exception.approveUrl());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PayPalApiException.class)
    public ResponseEntity<Map<String, Object>> payPal(PayPalApiException exception) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "PAYPAL_ERROR");
        body.put("status", exception.statusCode());
        body.put("name", exception.errorName());
        body.put("message", exception.getMessage());
        if (exception.debugId() != null) {
            body.put("debugId", exception.debugId());
        }
        if (exception.payerActionUrl() != null) {
            body.put("payerActionUrl", exception.payerActionUrl());
        }
        HttpStatus status = exception.statusCode() == 422
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, String> fieldError(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage()
        );
    }
}
