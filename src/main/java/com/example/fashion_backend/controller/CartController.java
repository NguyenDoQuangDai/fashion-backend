package com.example.fashion_backend.controller;

import com.example.fashion_backend.model.CartItem;
import com.example.fashion_backend.service.CartService;
import com.example.fashion_backend.security.UserPrincipal;
import org.springframework.security.core.Authentication;
import java.util.UUID;
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
    public List<CartItem> getCart(@RequestParam(value = "clientId", required = false) String clientId,
                                  Authentication authentication) {
        return cartService.getCartItems(userId(authentication), clientId);
    }

    @PostMapping("/cart")
    public ResponseEntity<?> addToCart(@RequestParam(value = "clientId", required = false) String clientId,
                                       @RequestBody CartItem item, Authentication authentication) {
        cartService.addToCart(userId(authentication), clientId, item);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/cart")
    public ResponseEntity<?> updateCart(@RequestParam(value = "clientId", required = false) String clientId,
                                        @RequestBody CartItem item, Authentication authentication) {
        cartService.updateQuantity(userId(authentication), clientId, item);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/cart/{productId}")
    public ResponseEntity<?> removeFromCart(@RequestParam(value = "clientId", required = false) String clientId,
                                            @PathVariable String productId, Authentication authentication) {
        cartService.removeItem(userId(authentication), clientId, productId);
        return ResponseEntity.ok().build();
    }

    private UUID userId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                ? principal.getUser().getId() : null;
    }
}
