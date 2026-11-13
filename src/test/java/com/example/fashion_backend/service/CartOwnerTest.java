package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CartOwnerTest {
    final CartRepository carts = mock(CartRepository.class);
    final CartService service = new CartService(carts, mock(ProductRepository.class));

    @Test void guestLookupCannotReadCartAfterLoginMerge() {
        UUID userId = UUID.randomUUID();
        CartEntity guest = new CartEntity();
        guest.setGuestId("browser");
        CartItemEntity item = new CartItemEntity();
        item.setProductId("p1"); item.setQuantity(2);
        guest.setItems(new ArrayList<>(List.of(item)));
        CartEntity user = new CartEntity(); user.setItems(new ArrayList<>());
        when(carts.findByGuestId("browser")).thenReturn(Optional.of(guest), Optional.empty());
        when(carts.findByUserId(userId)).thenReturn(Optional.of(user));

        service.mergeGuestCartToUser(userId, "browser");

        assertEquals("p1", user.getItems().get(0).getProductId());
        verify(carts).delete(guest);
        // This is the lookup used by the original CartController even after login.
        assertTrue(service.getCartItems("browser").isEmpty());
        assertEquals("p1", service.getCartItems(userId, "browser").get(0).getProductId());
        assertEquals(2, service.getCartItems(userId, "browser").get(0).getQuantity());
    }

    @Test void authenticatedMutationsIgnoreClientIdAndStayWithUserAcrossDevices() {
        UUID userId = UUID.randomUUID();
        CartEntity user = new CartEntity(); user.setItems(new ArrayList<>());
        when(carts.findByUserId(userId)).thenReturn(Optional.of(user));
        service.addToCart(userId, "device-a", new com.example.fashion_backend.model.CartItem("p1", 2));
        service.updateQuantity(userId, "device-b", new com.example.fashion_backend.model.CartItem("p1", 3));
        assertEquals(3, service.getCartItems(userId, "device-c").get(0).getQuantity());
        verify(carts, never()).findByGuestId(anyString());
        service.removeItem(userId, "other", "p1");
        assertTrue(user.getItems().isEmpty());
    }

    @Test void checkoutConsumesOnlyPurchasedItemsAndDoesNotTouchOtherUser() {
        UUID userId = UUID.randomUUID(), otherId = UUID.randomUUID();
        CartEntity user = new CartEntity(); user.setItems(new ArrayList<>());
        CartEntity other = new CartEntity(); other.setItems(new ArrayList<>());
        when(carts.findByUserId(userId)).thenReturn(Optional.of(user));
        when(carts.findByUserId(otherId)).thenReturn(Optional.of(other));
        service.addToCart(userId, null, new com.example.fashion_backend.model.CartItem("p1", 3));
        service.addToCart(userId, null, new com.example.fashion_backend.model.CartItem("p2", 1));
        service.addToCart(otherId, null, new com.example.fashion_backend.model.CartItem("p1", 5));
        service.removePurchasedItems(userId, null, List.of(new com.example.fashion_backend.model.CartItem("p1", 2)));
        assertEquals(1, user.getItems().get(0).getQuantity());
        assertEquals(2, user.getItems().size());
        assertEquals(5, other.getItems().get(0).getQuantity());
    }
}
