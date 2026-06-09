package com.example.fashion_backend.controller;

import com.example.fashion_backend.entity.OrderEntity;
import com.example.fashion_backend.model.Order;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api")
public class CheckoutController {
    private final OrderService orderService;

    public CheckoutController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody Order order, Authentication authentication) {
        UUID userId = null;
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            userId = principal.getUser().getId();
        }
        OrderEntity entity = orderService.createOrder(order, userId);
        order.setId(entity.getId().toString());
        Map<String, Object> resp = new HashMap<>();
        resp.put("orderId", order.getId());
        resp.put("status", "processing");
        resp.put("order", order);
        return ResponseEntity.ok(resp);
    }
}
