package com.example.fashion_backend.controller;

import com.example.fashion_backend.entity.OrderEntity;
import com.example.fashion_backend.model.Order;
import com.example.fashion_backend.security.UserPrincipal;
import com.example.fashion_backend.service.OrderService;
import com.example.fashion_backend.service.CartService;
import org.springframework.transaction.annotation.Transactional;
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
    private final CartService cartService;

    private final com.example.fashion_backend.service.PaymentService paymentService;
    public CheckoutController(OrderService orderService, com.example.fashion_backend.service.PaymentService paymentService,
                              CartService cartService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.cartService = cartService;
    }

    @PostMapping("/checkout/quote")
    public ResponseEntity<?> quote(@RequestBody Order order) {
        return ResponseEntity.ok(orderService.quote(order));
    }

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<?> checkout(@RequestBody Order order, Authentication authentication) {
        UUID userId = null;
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            userId = principal.getUser().getId();
        }
        boolean vnpay = order.getPaymentMethod() == com.example.fashion_backend.entity.PaymentMethod.VNPAY;
        var payment = vnpay ? paymentService.checkout(order, userId) : null;
        OrderEntity entity = vnpay ? payment.order() : orderService.createOrder(order, userId);
        // The UI clears checked-out items for both COD and pending VNPAY orders.
        // Keep server cart in sync so a later login/sync does not resurrect them.
        cartService.removePurchasedItems(userId, order.getClientId(), order.getItems());

        Map<String, Object> resp = new HashMap<>();
        resp.put("orderId", entity.getId().toString());
        resp.put("status", entity.getStatus());
        resp.put("order", orderService.mapToResponse(entity));
        if (payment != null) {
            resp.put("txnRef", payment.txnRef());
            resp.put("paymentAccessToken", payment.paymentAccessToken());
        }
        return ResponseEntity.ok(resp);
    }
}
