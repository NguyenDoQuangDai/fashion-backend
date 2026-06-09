package com.example.fashion_backend.controller.admin;

import com.example.fashion_backend.dto.admin.UpdateOrderRequest;
import com.example.fashion_backend.dto.admin.UpdateOrderStatusRequest;
import com.example.fashion_backend.dto.order.OrderResponse;
import com.example.fashion_backend.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderController {
    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> list() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(orderService.getOrder(UUID.fromString(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateOrderRequest request) {
        return ResponseEntity.ok(orderService.updateOrder(UUID.fromString(id), request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable String id,
            @Valid @RequestBody UpdateOrderStatusRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(UUID.fromString(id), request.getStatus()));
    }
}
