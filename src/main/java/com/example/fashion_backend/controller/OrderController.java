package com.example.fashion_backend.controller;

import com.example.fashion_backend.dto.order.OrderResponse;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.OrderService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> list(Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        UUID userId = principal.getUser().getId();
        return ResponseEntity.ok(orderService.getOrdersForUser(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable String id, Authentication authentication) {
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        OrderResponse response = orderService.getOrder(UUID.fromString(id));
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }
}
