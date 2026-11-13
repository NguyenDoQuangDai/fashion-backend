package com.example.fashion_backend.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(Exception ex) {
        return ResponseEntity.status(413).body(Map.of("code", "IMAGE_TOO_LARGE", "message", "Tệp tải lên vượt quá dung lượng cho phép."));
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_UPLOAD", "message", "Vui lòng chọn tệp tải lên."));
    }
    @ExceptionHandler(com.example.fashion_backend.service.AddressException.class)
    public ResponseEntity<Map<String, Object>> handleAddress(com.example.fashion_backend.service.AddressException ex) {
        Map<String,Object> body = new HashMap<>();
        body.put("code", ex.getCode()); body.put("message", ex.getMessage());
        var response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfter() != null) {
            body.put("retryAfter", ex.getRetryAfter()); response.header("Retry-After", ex.getRetryAfter().toString());
        }
        return response.body(body);
    }
    @ExceptionHandler(com.example.fashion_backend.service.PaymentException.class)
    public ResponseEntity<Map<String, Object>> handlePayment(com.example.fashion_backend.service.PaymentException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }
    @ExceptionHandler(com.example.fashion_backend.service.ShippingException.class)
    public ResponseEntity<Map<String, Object>> handleShipping(com.example.fashion_backend.service.ShippingException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", ex.getMessage());
        body.put("code", ex.getCode());
        if (ex.getQuote() != null) body.put("quote", ex.getQuote());
        var response = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfter() != null) {
            body.put("retryAfter", ex.getRetryAfter());
            response.header("Retry-After", ex.getRetryAfter().toString());
        }
        return response.body(body);
    }
    @ExceptionHandler(com.example.fashion_backend.service.DeliveryAddressService.AddressResolutionException.class)
    public ResponseEntity<Map<String, Object>> handleAddressResolution(RuntimeException ex) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableRequest(Exception ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ. Vui lòng kiểm tra và chọn lại địa chỉ.");
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> handleStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", java.util.Objects.toString(ex.getReason(), "Request conflict")));
    }
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String,Object>> handleInventoryBusy(RuntimeException ex) {
        return ResponseEntity.status(409).body(Map.of("code", "INVENTORY_BUSY", "message", "Inventory is busy; retry after reloading"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", "Validation failed");
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        body.put("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled API error", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
