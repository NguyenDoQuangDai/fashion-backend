package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.payment.PaymentDtos.*;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/vnpay")
public class VnpayPaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VnpayPaymentController.class);
    private final PaymentService payments;
    public VnpayPaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping("/create")
    public ResponseEntity<PaymentUrl> create(@Valid @RequestBody CreateRequest request, Authentication auth,
            @RequestHeader(value = "X-Payment-Token", required = false) String token, HttpServletRequest servlet) {
        // Frontend uses Vite's local proxy; no untrusted X-Forwarded-For header is accepted here.
        String ip = servlet.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) ip = "127.0.0.1";
        return fresh(payments.create(request.orderId(), userId(auth), token, ip));
    }

    @GetMapping("/{txnRef}/status")
    public ResponseEntity<Status> status(@PathVariable String txnRef, Authentication auth,
            @RequestHeader(value = "X-Payment-Token", required = false) String token) {
        return fresh(payments.status(txnRef, userId(auth), token));
    }

    @PostMapping("/verify-return")
    public ResponseEntity<ReturnResult> verifyReturn(@RequestBody Map<String, String> fields, Authentication auth,
            @RequestHeader(value = "X-Payment-Token", required = false) String token) {
        return fresh(payments.verifyReturn(fields, userId(auth), token));
    }

    @GetMapping("/ipn")
    public ResponseEntity<IpnReply> ipn(@RequestParam MultiValueMap<String, String> params) {
        try {
            if (params.values().stream().anyMatch(values -> values.size() != 1)) return fresh(new IpnReply("99", "Invalid request"));
            return fresh(payments.ipn(params.toSingleValueMap()));
        } catch (Exception ex) {
            // Transaction/commit failures reach this boundary after rollback. Never return a false 00.
            log.warn("VNPAY IPN confirmation failed ({})", ex.getClass().getSimpleName());
            return fresh(new IpnReply("99", "Unable to confirm transaction"));
        }
    }

    private UUID userId(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof UserPrincipal p ? p.getUser().getId() : null;
    }
    private <T> ResponseEntity<T> fresh(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
