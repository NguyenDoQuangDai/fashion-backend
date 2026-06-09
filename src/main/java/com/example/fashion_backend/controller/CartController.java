package com.example.fashion_backend.controller;

import com.example.fashion_backend.model.CartItem;
import com.example.fashion_backend.service.CartService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/cart")
    public List<CartItem> getCart(@RequestParam(value = "clientId", required = false) String clientId) {
        return cartService.getCartItems(clientId);
    }

    @PostMapping("/cart")
    public ResponseEntity<?> addToCart(@RequestParam(value = "clientId", required = false) String clientId,
                                       @RequestBody CartItem item) {
        cartService.addToCart(clientId, item);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/cart")
    public ResponseEntity<?> updateCart(@RequestParam(value = "clientId", required = false) String clientId,
                                        @RequestBody CartItem item) {
        cartService.updateQuantity(clientId, item);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/cart/{productId}")
    public ResponseEntity<?> removeFromCart(@RequestParam(value = "clientId", required = false) String clientId,
                                            @PathVariable String productId) {
        cartService.removeItem(clientId, productId);
        return ResponseEntity.ok().build();
    }
}
