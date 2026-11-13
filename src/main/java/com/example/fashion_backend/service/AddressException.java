package com.example.fashion_backend.service;

public class AddressException extends RuntimeException {
    private final int status;
    private final String code;
    private final Integer retryAfter;
    public AddressException(int status, String code, String message) { this(status, code, message, null); }
    public AddressException(int status, String code, String message, Integer retryAfter) {
        super(message); this.status = status; this.code = code; this.retryAfter = retryAfter;
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Integer getRetryAfter() { return retryAfter; }
}
