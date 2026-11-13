package com.example.fashion_backend.service;

import com.example.fashion_backend.dto.order.CheckoutQuoteResponse;

public class ShippingException extends RuntimeException {
    private final int status;
    private final String code;
    private final Integer retryAfter;
    private final CheckoutQuoteResponse quote;

    public ShippingException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ShippingException(int status, String code, String message, Integer retryAfter, CheckoutQuoteResponse quote) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
        this.quote = quote;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Integer getRetryAfter() { return retryAfter; }
    public CheckoutQuoteResponse getQuote() { return quote; }
}
