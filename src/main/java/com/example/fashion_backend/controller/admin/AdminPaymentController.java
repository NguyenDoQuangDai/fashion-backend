package com.example.fashion_backend.controller.admin;

import com.example.fashion_backend.dto.payment.PaymentDtos.*;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.PaymentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/orders/{id}/payment")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentController {
    private final PaymentService payments;
    public AdminPaymentController(PaymentService payments) { this.payments = payments; }
    @GetMapping
    public ResponseEntity<Map<String, Object>> details(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.adminDetails(id));
    }
    @PostMapping("/resolve")
    public ResponseEntity<Status> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request, Authentication auth) {
        UserPrincipal actor = (UserPrincipal) auth.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            payments.resolve(id, request, actor.getUser().getId(), actor.getUsername()));
    }
}
