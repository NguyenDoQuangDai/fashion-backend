package com.example.fashion_backend.service;

import com.example.fashion_backend.entity.*;
import com.example.fashion_backend.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderPaymentRulesTest {
    @org.junit.jupiter.api.BeforeEach void bindInventory() { InventoryTestSupport.bind(service, products); }
    OrderRepository repo = mock(OrderRepository.class);
    ProductRepository products = mock(ProductRepository.class);
    OrderService service = new OrderService(repo, products, mock(MailService.class), mock(DeliveryAddressService.class), mock(ShippingService.class));
    OrderEntity order(PaymentMethod method, PaymentStatus status) {
        var o = new OrderEntity(); o.setId(UUID.randomUUID()); o.setStatus("processing"); o.setTotal(BigDecimal.TEN);
        o.setPaymentMethod(method); o.setPaymentStatus(status); o.setItems(List.of());
        when(repo.findLockedById(o.getId())).thenReturn(Optional.of(o)); when(repo.save(o)).thenReturn(o); return o;
    }
    @Test void codCompletionConfirmsCollectionAndCancellationFailsUncollectedPayment() {
        var o = order(PaymentMethod.COD, PaymentStatus.PENDING); service.updateStatus(o.getId(), "completed");
        assertEquals(PaymentStatus.PAID, o.getPaymentStatus()); assertNotNull(o.getPaidAt());
        var cancelled = order(PaymentMethod.COD, PaymentStatus.PENDING); service.updateStatus(cancelled.getId(), "cancelled");
        assertEquals(PaymentStatus.FAILED, cancelled.getPaymentStatus());
    }
    @Test void unpaidVnpayCannotShipOrCompleteButPaidCancellationDoesNotImplyRefund() {
        var o = order(PaymentMethod.VNPAY, PaymentStatus.PENDING);
        assertThrows(PaymentException.class, () -> service.updateStatus(o.getId(), "shipping"));
        assertThrows(PaymentException.class, () -> service.updateStatus(o.getId(), "completed"));
        assertEquals("processing", o.getStatus()); verify(repo, never()).save(o);
        o.setPaymentStatus(PaymentStatus.PAID); service.updateStatus(o.getId(), "cancelled");
        assertEquals("cancelled", o.getStatus()); assertEquals(PaymentStatus.PAID, o.getPaymentStatus());
    }
}
